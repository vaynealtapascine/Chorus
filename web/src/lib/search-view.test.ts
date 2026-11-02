import { readFileSync } from 'node:fs';
import { beforeAll, expect, it, vi } from 'vitest';
vi.mock('./router.svelte', () => ({router:{go:vi.fn()}}));
import { core, loadCore } from './core';
import { messageById } from './data';
import { searchView, contextMessages } from './search-view';
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
