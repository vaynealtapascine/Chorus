// Mount the real client component: SSR cannot exercise $state identity or subscriptions.
import { beforeAll, afterAll, expect, it } from 'vitest';
import { chromium, type Browser, type Page } from '@playwright/test';
import { rolldown } from 'rolldown';
import { compile } from 'svelte/compiler';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url)).replaceAll('\\','/').replace(/\/$/,'');
let browser:Browser, script:string;
beforeAll(async()=>{
 const mocks:Record<string,string>={
  '../data':`export const channels=()=>[];export const members=()=>[];export const messageById=()=>undefined;export const customEmojis=()=>[];export const reactions=()=>new Map();`,
  '../search':`export class SearchIndex{find(){return []}apply(){}}export class JournalIndex{searchPosts(){return []}searchSwitches(){return []}apply(){}}export const parseSearch=q=>({terms:q?[q]:[]});`,
  '../core':`export const core={searchParse:q=>({words:[q]})};`,
  '../sync/client':`class Client{accountId='a';status='live';device={session:'old'};listeners=new Set();subscribe(f){this.listeners.add(f);return()=>this.listeners.delete(f)}projectionListeners=new Set();subscribeProjection(f){this.projectionListeners.add(f);return()=>this.projectionListeners.delete(f)}notifyProjection(){for(const f of this.projectionListeners)f({rows:{}},{})}frontHistory(){return null}notify(){for(const f of this.listeners)f()}}export const sync=new Client();window.searchSync=sync;`,
  '../sync/device':`export const apiBase=()=>'/api/v1';`,
  '../http':`window.requests=[];export function apiFetch(url,options){return new Promise(resolve=>window.requests.push({url,session:options.headers.authorization,resolve:items=>resolve({ok:true,json:async()=>items})}))}`,
  '../posts':`export const posts=()=>[];`,
  '../journal-social':`export const savedPosts=()=>new Set();export const socialIndex=()=>({});`,
  '../search-view':`export const searchView=()=>()=>true;export const searchContexts=()=>new Map();export const remoteMessage=x=>x;export const searchAncestors=async(p,fetch,wanted)=>{window.ancestorScopes=[...wanted];return new Map()};`,
  './kit/UnderlineTabs.svelte':`<script>let {tabs,value=$bindable()}=$props();</script><div role="tablist">{#each tabs as t}<button role="tab" onclick={()=>value=t.id}>{t.label}</button>{/each}</div>`,
  '../polls':`export const pollOf=()=>null;`
 };
 const bundle=await rolldown({input:'test:entry',platform:'browser',resolve:{conditionNames:['browser','import','default']},plugins:[{
  name:'mounted-search',resolveId(source,importer){importer=importer?.replaceAll('\\','/');if(source.startsWith('test:'))return source;
   if(importer?.endsWith('/ui/Search.svelte')){if(source in mocks)return 'test:mock:'+source;if(source.endsWith('.svelte'))return 'test:card.svelte';}
   if(source==='svelte')return root+'/node_modules/svelte/src/index-client.js';
   if(source.startsWith('svelte/internal/'))return root+'/node_modules/svelte/src/internal/'+source.slice('svelte/internal/'.length)+(source==='svelte/internal/client'?'/index.js':'.js');
  },load(id){if(id==='test:entry')return `import {mount,unmount} from '${root}/node_modules/svelte/src/index-client.js';import Search from '${root}/src/lib/ui/Search.svelte';const app=mount(Search,{target:document.body,props:{projection:{rows:{pref:{},post:{}}},initialScope:'posts'}});window.stopSearch=()=>unmount(app);`;
   if(id.startsWith('test:mock:'))return mocks[id.slice('test:mock:'.length)];if(id==='test:card.svelte')return '<script>let {post}=$props();</script><article>{post?.text}</article>';
   if(id.endsWith('.svelte'))return readFileSync(id,'utf8');
  },transform(code,id){if(id.endsWith('.svelte'))return compile(code,{filename:id,generate:'client',css:'injected'}).js.code;}
 }]});
 try{script=(await bundle.generate({format:'iife'})).output[0].code;}finally{await bundle.close();}
 const chrome='C:/Program Files/Google/Chrome/Application/chrome.exe';
 browser=await chromium.launch({headless:true,...(existsSync(chrome)?{executablePath:chrome}:{})});
},30000);
afterAll(async()=>browser?.close(),30000);
async function start(){const page=await browser.newPage();await page.setContent('<html><body></body></html>');await page.addScriptTag({content:script});await page.getByRole('searchbox').fill('needle');await calls(page,1);return page;}
async function calls(page:Page,n:number){await page.waitForFunction(n=>(window as any).requests.length>=n,n,{timeout:4000});}
async function reply(page:Page,i:number,text:string,cursor:string|null='next'){await page.evaluate(({i,text,cursor})=>(window as any).requests[i].resolve({items:[{id:text,text,occurred_at:i+1,channel_id:'channel-'+i}],next_cursor:cursor}),{i,text,cursor});}
async function transition(page:Page,status:string,session:string){await page.evaluate(({status,session})=>{const s=(window as any).searchSync;s.status=status;s.device={session};s.notify();},{status,session});}
it('renders successful foreign results and appends pagination in the mounted component',async()=>{
 const page=await start();try{await reply(page,0,'FOREIGN_FIRST');await expect.poll(()=>page.locator('body').innerText()).toContain('FOREIGN_FIRST');await page.getByRole('button',{name:'Load more',exact:true}).click();await calls(page,2);await reply(page,1,'FOREIGN_SECOND',null);await expect.poll(()=>page.locator('body').innerText()).toContain('FOREIGN_SECOND');expect(await page.locator('body').innerText()).toContain('FOREIGN_FIRST');}finally{await page.close();}
});
it('clears foreign results on disconnect and restarts the unchanged query on reconnect',async()=>{
 const page=await start();try{await reply(page,0,'PRIVATE_OLD');await expect.poll(()=>page.locator('body').innerText()).toContain('PRIVATE_OLD');await transition(page,'offline','old');await expect.poll(()=>page.locator('body').innerText()).not.toContain('PRIVATE_OLD');await transition(page,'live','old');await calls(page,2);await reply(page,1,'FRESH');await expect.poll(()=>page.locator('body').innerText()).toContain('FRESH');}finally{await page.close();}
});
it('ignores first-page and same-cursor pagination responses from an older session generation',async()=>{
 const page=await start();try{
  await transition(page,'live','new');await calls(page,2);await reply(page,1,'NEW_SESSION');await expect.poll(()=>page.locator('body').innerText()).toContain('NEW_SESSION');await reply(page,0,'STALE_FIRST');
  await page.getByRole('button',{name:'Load more',exact:true}).click();await calls(page,3);
  await transition(page,'offline','new');await transition(page,'live','old');await calls(page,4);await reply(page,3,'RECONNECTED');await expect.poll(()=>page.locator('body').innerText()).toContain('RECONNECTED');await reply(page,2,'STALE_MORE');
  await page.getByRole('button',{name:'Load more',exact:true}).click();await calls(page,5);await reply(page,4,'VALID_MORE',null);await expect.poll(()=>page.locator('body').innerText()).toContain('VALID_MORE');
  const body=await page.locator('body').innerText();expect(body).not.toContain('STALE_FIRST');expect(body).not.toContain('STALE_MORE');expect(body).not.toContain('NEW_SESSION');
  expect(await page.evaluate(()=>(window as any).requests.map((r:any)=>r.session))).toEqual(['Bearer old','Bearer new','Bearer new','Bearer old','Bearer old']);
  await page.evaluate(()=>(window as any).stopSearch());expect(await page.evaluate(()=>(window as any).searchSync.listeners.size)).toBe(0);
 }finally{await page.close();}
});

it('invalidates a retained projection generation and rejects late A-to-B-to-A query responses',async()=>{
 const page=await start();try{
  await page.getByRole('searchbox').fill('changed');await calls(page,2);
  await page.getByRole('searchbox').fill('needle');await calls(page,3);
  await reply(page,2,'CURRENT_NEEDLE');await expect.poll(()=>page.locator('body').innerText()).toContain('CURRENT_NEEDLE');
  await reply(page,0,'STALE_NEEDLE');await reply(page,1,'STALE_CHANGED');
  await page.evaluate(()=>(window as any).searchSync.notifyProjection());await calls(page,4);
  expect(await page.locator('body').innerText()).not.toContain('CURRENT_NEEDLE');
  await reply(page,3,'NEW_PROJECTION');await expect.poll(()=>page.locator('body').innerText()).toContain('NEW_PROJECTION');
  const body=await page.locator('body').innerText();expect(body).not.toContain('STALE_NEEDLE');expect(body).not.toContain('STALE_CHANGED');
 }finally{await page.close();}
});

it('revalidates missing ancestors for retained pages as pagination adds other channels',async()=>{
 const page=await start();try{
  await page.getByRole('tab',{name:'Messages',exact:true}).click();await calls(page,2);
  await reply(page,1,'FIRST_THREAD');await expect.poll(()=>page.getByRole('button',{name:'Load more',exact:true}).count()).toBe(1);
  expect(await page.evaluate(()=>(window as any).ancestorScopes)).toEqual(['channel-1']);
  await page.getByRole('button',{name:'Load more',exact:true}).click();await calls(page,3);await reply(page,2,'SECOND_THREAD');
  await expect.poll(()=>page.evaluate(()=>(window as any).ancestorScopes)).toEqual(['channel-1','channel-2']);
 }finally{await page.close();}
});
