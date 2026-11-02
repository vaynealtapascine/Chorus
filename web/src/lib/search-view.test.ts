import { readFileSync } from 'node:fs';
import { beforeAll, expect, it, vi } from 'vitest';
vi.mock('./router.svelte', () => ({router:{go:vi.fn()}}));
import { core, loadCore } from './core';
import { messageById } from './data';
import { searchView, contextMessages, searchAncestors } from './search-view';
import { SearchIndex, JournalIndex, parseSearch } from './search';
import { savedPosts } from './journal-social';
import type { Projection } from './sync/client';
const fixture=JSON.parse(readFileSync(new URL('../../../fixtures/ui_search.json',import.meta.url),'utf8'));
beforeAll(async()=>loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm',import.meta.url))));
for(const c of fixture.cases) it(c.name,()=>{
  const p=structuredClone(fixture.projection) as Projection;
  p.fronts.account={current:c.front.map((id:string)=>({subject_type:'member',subject_id:id,level:'front',is_primary:false})),switches:[],intervals:[]} as never;
  const visible=searchView(p,'account');
  const all=Object.keys(p.rows.message).map(id=>messageById(p,id)!);
  expect(all.filter(visible).map(m=>m.id).sort()).toEqual([...c.visible].sort());
  const match=messageById(p,'m-match')!;
  expect(contextMessages(match,all,visible).map(m=>m.id)).toEqual(c.context);
  const hits=new SearchIndex(p).find(core.searchParse('needle'),30,100).filter(h=>visible(messageById(p,h.id)));
  expect(hits.map(h=>h.id).sort()).toEqual([...c.visible].sort());
  expect(hits.filter(h=>h.channel_id==='c' && h.text.includes('private-message'))).toEqual([]);
});
it('keeps Journal post/entry/reply and Saved scopes separate from chat and switches',()=>{
  const p=fixture.projection as Projection;
  const index=new JournalIndex(p,'account');
  expect(index.searchPosts(parseSearch('garden')).map(h=>h.id).sort()).toEqual(['entry','note','reply']);
  const saved=savedPosts(p.rows.pref,'account');
  expect(index.searchPosts(parseSearch('garden')).filter(h=>saved.has(h.id)).map(h=>h.id)).toEqual(['entry']);
  expect(index.searchPosts(parseSearch('needle'))).toEqual([]);
});
it('rechecks front changes and permission changes against retained objects',()=>{
 const p=structuredClone(fixture.projection) as Projection;
 p.fronts.account={current:[{subject_type:'member',subject_id:'rin',level:'front'}],switches:[],intervals:[]} as never;
 const retained=messageById(p,'private-message')!;
 expect(searchView(p,'account')(retained)).toBe(true);
 p.fronts.account.current=[];
 expect(searchView(p,'account')(retained)).toBe(false);
 p.rows.channel_permission['c|account|account']={exists:true,fields:{deny:['view']}};
 p.rows.space.s.fields.created_by_account_id='other';
 expect(searchView(p,'account')(messageById(p,'m-match'))).toBe(false);
});

import { render } from 'svelte/server';
import Message from './ui/Message.svelte';
it('reuses the complete message renderer, without mutation controls, preserving CW and rich text',()=>{
 const p=structuredClone(fixture.projection) as Projection;
 const m=messageById(p,'m-match')!;
 const text='full message '.repeat(60)+'TAIL_BEYOND_EXCERPT';
 const noop=()=>{};
 const props={m:{...m,text,entities:[{type:'bold',offset:0,length:4}] as never[]},cont:false,people:new Map(),dark:true,lookup:()=>undefined,mine:false,
 onreply:noop,onreplyin:noop,onquote:noop,onedit:noop,ondelete:noop,onrestore:noop,onpin:noop,onforward:noop,onselect:noop,selecting:false,selected:false,onthread:noop,reacts:undefined,speaker:null,onreact:noop,emojiById:new Map(),cwAutoExpand:false,readOnly:true};
 const html=render(Message,{props}).body;
 expect(html).toContain('TAIL_BEYOND_EXCERPT');
 expect(html).not.toContain('Message actions');
 expect(html).not.toContain('Delete</button>');
 expect(render(Message,{props:{...props,m:{...props.m,cw:'Warning'}}}).body).not.toContain('TAIL_BEYOND_EXCERPT');
});
it('local pending privacy wins over a stale server copy of the same neighbor',()=>{
 const p=structuredClone(fixture.projection) as Projection;
 const stale=messageById(p,'m-match')!;
 p.rows.message['m-match']={...p.rows.message['m-match'],fields:{...p.rows.message['m-match'].fields,visibility:{mode:'members',member_ids:['rin']}}};
 expect(searchView(p,'account')(stale)).toBe(false);
});

import Search from './ui/Search.svelte';
import { sync } from './sync/client';
it('shows an unavailable chat state without its private title after a front change',()=>{
 const p=structuredClone(fixture.projection) as Projection;
 p.rows.channel.private.fields.name='SECRET_ROOM_TITLE';
 const previous=sync.device;
 Object.assign(sync,{device:{account_id:'account'}});
 try {
  const html=render(Search,{props:{projection:p,channelId:'private',dark:true}}).body;
  expect(html).toContain('This chat is unavailable to the current front.');
  expect(html).not.toContain('SECRET_ROOM_TITLE');
 } finally {Object.assign(sync,{device:previous});}
});

it('excludes archived chat matches and inherited context before counting',()=>{
 const p=structuredClone(fixture.projection) as Projection;
 p.rows.channel.c.fields.archived_at=42;
 const visible=searchView(p,'account');
 expect(Object.keys(p.rows.message).map(id=>messageById(p,id)!).filter(visible).filter(m=>m.channel_id==='c')).toEqual([]);
 expect(contextMessages(messageById(p,'m-match')!,Object.keys(p.rows.message).map(id=>messageById(p,id)!),visible)).toEqual([]);
});
it('validates a guarded missing parent while local privacy and tombstones win',()=>{
 const p=structuredClone(fixture.projection) as Projection;
 p.fronts.account={current:[{subject_type:'member',subject_id:'rin',level:'front'}],switches:[],intervals:[]} as never;
 p.rows.channel.thread.fields.parent_message_id='private-message';
 const parent=messageById(p,'private-message')!;
 const hit=Object.keys(p.rows.message).map(id=>messageById(p,id)!).find(m=>m.channel_id==='thread')!;
 delete p.rows.message[parent.id];
 const ancestors=new Map([[parent.id,parent]]);
 expect(searchView(p,'account')(hit)).toBe(false);
 expect(searchView(p,'account',ancestors)(hit)).toBe(true);
 p.rows.message[parent.id]={exists:true,fields:{channel_id:parent.channel_id,text:'local',occurred_at:parent.occurred_at,visibility:{mode:'members',member_ids:['missing']}}};
 expect(searchView(p,'account',ancestors)(hit)).toBe(false);
 p.rows.message[parent.id].fields.deleted_at=30;
 expect(searchView(p,'account',ancestors)(hit)).toBe(false);
 delete p.rows.message[parent.id];
 p.fronts.account.current=[];
 expect(searchView(p,'account',ancestors)(hit)).toBe(false);
});

it('hydrates guarded thread dependencies, and never fetches over a local tombstone',async()=>{
 const p=structuredClone(fixture.projection) as Projection;
 p.rows.channel.thread.fields.parent_message_id='private-message';
 const parent=messageById(p,'private-message')!;
 delete p.rows.message[parent.id];
 const fetch=vi.fn(async(id:string)=>id===parent.id?parent:undefined);
 const ancestors=await searchAncestors(p,fetch);
 expect(fetch).toHaveBeenCalledWith(parent.id);
 expect(ancestors.get(parent.id)).toEqual(parent);
 const hit=Object.keys(p.rows.message).map(id=>messageById(p,id)!).find(m=>m.channel_id==='thread')!;
 expect(searchView(p,'account',ancestors)(hit)).toBe(false); // Private parent, no eligible front.
 p.fronts.account={current:[{subject_type:'member',subject_id:'rin',level:'front'}],switches:[],intervals:[]} as never;
 expect(searchView(p,'account',ancestors)(hit)).toBe(true);
 const denied=await searchAncestors(p,async()=>undefined);
 expect(searchView(p,'account',denied)(hit)).toBe(false);
 p.rows.message[parent.id]={exists:true,fields:{deleted_at:30}};
 fetch.mockClear();
 await searchAncestors(p,fetch);
 expect(fetch).not.toHaveBeenCalledWith(parent.id);
 p.rows.channel.private.fields.archived_at=50;
 expect(searchView(p,'account',ancestors)(hit)).toBe(false);
});

it('malformed channel membership fails closed in hits, neighbors and thread ancestors',()=>{
 for(const ids of [['rin',42],[42],{},'rin']) {
  const p=structuredClone(fixture.projection) as Projection;
  p.fronts.account={current:[{subject_type:'member',subject_id:'rin',level:'front'}],switches:[],intervals:[]} as never;
  p.rows.channel.c.fields.member_ids=ids;
  p.rows.channel.thread.fields.parent_message_id='m-match';
  const view=searchView(p,'account');
  const hit=messageById(p,'m-match')!;
  expect(view(hit)).toBe(false);
  expect(view(messageById(p,'thread-message'))).toBe(false);
  expect(contextMessages(hit,Object.keys(p.rows.message).map(id=>messageById(p,id)!),view)).toEqual([]);
 }
});
