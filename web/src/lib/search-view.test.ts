import { readFileSync } from 'node:fs';
import { beforeAll, expect, it, vi } from 'vitest';
vi.mock('./router.svelte', () => ({router:{go:vi.fn()}}));
import { core, loadCore } from './core';
import { project } from './core/pkg/chorus_wasm.js';
import { channels, messageById } from './data';
import { chatShown } from './chat-visible';
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
  expect(html).toContain('Private chat');
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

const subsystemFixture=JSON.parse(readFileSync(new URL('../../../fixtures/ui_search_subsystems.json',import.meta.url),'utf8'));
for(const c of subsystemFixture.cases) it(`subsystem search: ${c.name}`,()=>{
 const p=structuredClone(subsystemFixture.projection) as Projection;
 for(const [table,rows] of Object.entries(c.rows) as [string,Record<string,Record<string,unknown>>][]) {
  p.rows[table] ??= {};
  for(const [id,fields] of Object.entries(rows)) p.rows[table][id]={exists:true,fields:{...p.rows[table][id]?.fields,...fields}};
 }
 Object.assign(p.sets.group_membership,c.sets);
 p.fronts.account.current=c.front.map((id:string)=>({subject_type:'member',subject_id:id,level:c.level,is_primary:false}));
 const all=Object.keys(p.rows.message).map(id=>messageById(p,id)!);
 const visible=searchView(p,'account');
 expect(all.filter(visible).map(m=>m.id).sort()).toEqual(c.visible);
 expect(contextMessages(messageById(p,'hit')!,all,visible).map(m=>m.id)).toEqual(c.context);
 const hits=new SearchIndex(p).find(core.searchParse('needle'),30,100).filter(h=>visible(messageById(p,h.id)));
 expect(hits.map(h=>h.id).sort()).toEqual(c.visible);
 // Keep the normal guarded jump usable for newly eligible subsystem members, too.
 if(c.name==='nested active member includes subsystem chats and thread ancestors') {
  const shown=chatShown(p,'account');
  expect(shown(channels(p).find(c=>c.id==='root'))).toBe(true);
  expect(shown(channels(p).find(c=>c.id==='private-dm'))).toBe(true);
 }
});

// 0.1.706: every chat listed but shut. Ops shaped as the server writes them (auth.rs, spaces.rs),
// projected by the real core: a space's creator is its owner, joins are a set, a role is its own row.
it('opens what a member may see, from the core projection of real space ops', () => {
  const me = '0192f8c2-0000-7000-8000-0000000000a1', friend = '0192f8c2-0000-7000-8000-0000000000a2';
  const id = (n: number) => `0192f8c2-0000-7000-8000-${String(n).padStart(12, '0')}`;
  const ops: unknown[] = [];
  const op = (space: string, kind: string, entity: string, payload: object, by = me) => {
    const n = ops.length + 1;
    ops.push({ id: id(1000 + n), kind, scope: `space:${space}`, entity_id: entity, hlc: `${(0x1a0c4506c00 + n).toString(16).padStart(12, '0')}-0000-00000001`,
      device_at: 1790000000000 + n, payload, account_id: by, seq: n });
  };
  const space = (s: string, kind: string, owner: string, ...joins: string[]) => {
    op(s, 'space.create', s, { kind, name: kind }, owner);
    for (const a of joins) op(s, 'space.join', s, { account_id: a }, owner);
  };
  const said = new Map<string, string>();
  const channel = (s: string, c: string, by: string) => {
    op(s, 'channel.create', c, { space_id: s, kind: 'text', name: 'chat' }, by);
    said.set(c, id(3000 + ops.length));
    op(s, 'message.send', said.get(c)!, { channel_id: c, text: 'hello' }, by);
  };
  const shut = (s: string, c: string, by: string) => op(s, 'channel.set_permission', c, { target_type: 'role', target_id: 'everyone', allow: [], deny: ['view'] }, by);
  space(id(101), 'internal', me, me); channel(id(101), id(201), me);
  space(id(102), 'shared', friend, friend, me); channel(id(102), id(202), friend);
  channel(id(102), id(203), friend); shut(id(102), id(203), friend);
  space(id(104), 'shared', me, me, friend); channel(id(104), id(205), me); shut(id(104), id(205), me);
  space(id(105), 'shared', friend, friend, me); channel(id(105), id(206), friend);
  op(id(105), 'space.set_role', id(105), { account_id: me, role: 'admin' }, friend);
  op(id(105), 'space.leave', id(105), { account_id: me }, me);
  const p = JSON.parse(project(JSON.stringify(ops))) as Projection;
  const sees = (account: string, c: number) => searchView(p, account)(messageById(p, said.get(id(c))!));
  expect([201, 202, 205].map((c) => sees(me, c))).toEqual([true, true, true]);
  expect(sees(me, 203)).toBe(false);
  expect(sees(me, 206)).toBe(false);
  expect(sees(friend, 203)).toBe(true);
});
