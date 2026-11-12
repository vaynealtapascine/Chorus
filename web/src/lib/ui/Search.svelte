<script lang="ts">
  import EmptyState from './kit/EmptyState.svelte';
  import UnderlineTabs from './kit/UnderlineTabs.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import AttachmentView from './AttachmentView.svelte';
  import { libraryOf, setOperator } from '../search-library';
  import { onMount } from 'svelte';
  import { channels, members, messageById, customEmojis, reactions as reactionRows, type MessageRow } from '../data';
  import { JournalIndex, SearchIndex, parseSearch } from '../search';
  import { core } from '../core';
  import { sync, type Projection } from '../sync/client';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { posts as postRows, type PostRow } from '../posts';
  import { savedPosts, socialIndex } from '../journal-social';
  import { searchView, searchContexts, searchAncestors, remoteMessage, type SearchMessage } from '../search-view';
  import type { FriendPost } from '../friends-feed';
  import { pollOf } from '../polls';
  import PollCard from './PollCard.svelte';
  import Message from './Message.svelte';
  import PostCard from './PostCard.svelte';
  import FriendPostCard from './FriendPostCard.svelte';

  let { projection, dark = true, channelId, onclose, onopen, initialScope = 'messages' }: {
    projection: Projection; dark?: boolean; channelId?: string; onclose?: () => void; onopen?: (m:MessageRow) => void;
    initialScope?: Tab;
  } = $props();
  type Tab = 'chats' | 'messages' | 'media' | 'files' | 'links' | 'posts' | 'saved' | 'switches';
  const TABS: { id: Tab; label: string }[] = [{ id: 'chats', label: 'Chats' }, { id: 'messages', label: 'Messages' }, { id: 'media', label: 'Media' }, { id: 'files', label: 'Files' },
    { id: 'links', label: 'Links' }, { id: 'posts', label: 'Journal' }, { id: 'saved', label: 'Saved' }, { id: 'switches', label: 'Switches' }];
  const LIBRARY = ['chats', 'media', 'files', 'links'];
  let query = $state('');
  let tab = $state<Tab>('messages');
  let picking = $state<'from' | 'in' | 'date' | null>(null);
  $effect(() => { tab = initialScope; });
  let revision = $state(0);
  let index: SearchIndex | null = null, journal: JournalIndex | null = null;
  type Hit = SearchMessage & { context_before?: MessageRow | null; context_after?: MessageRow | null };
  let remote = $state<Hit[]>([]), remotePosts = $state<FriendPost[]>([]);
  let cursor = $state<string | null>(null), loading = $state(false), error = $state('');
  // SyncClient is a plain class. Only connection changes replace this raw snapshot.
  let connection = $state.raw({status:sync.status, session:sync.device?.session, account:sync.accountId});
  type Request = {projection:Projection; key:string; connection:typeof connection; revision:number; url:string; scope:typeof scope};
  let active = $state.raw<Request|null>(null), owner = $state.raw<Request|null>(null);
  let ancestors = $state.raw<ReadonlyMap<string,MessageRow>>(new Map());
  const scope = $derived(channelId ? 'messages' : tab);
  const key = $derived(`${scope}:${channelId ?? ''}:${query}`);
  const current = $derived(owner !== null && owner === active && owner.projection === projection && owner.key === key && owner.connection === connection && owner.revision===revision && connection.status === 'live');
  const visible = $derived(searchView(projection, connection.account, current ? ancestors : new Map()));
  const people = $derived.by(() => {
    const map=new Map(members(projection).map(m=>[m.id,m]));
    if(current) for(const hit of remote) for(const m of [hit,hit.context_before,hit.context_after]) if(m && visible(m))
      for(const a of (m as SearchMessage).author_cards ?? []) if(!map.has(a.id)) map.set(a.id,{...a,name:a.name??'Someone',color:a.color??'#A09184',sigils:a.sigils??[],proxy_tags:[],archived:false,deleted:false});
    return map;
  });
  const emojiById = $derived(new Map(customEmojis(projection).map(e => [e.id,e])));
  const saved = $derived(savedPosts(projection.rows.pref ?? {},connection.account));
  const entries = $derived(postRows(projection).filter(p => !p.deleted));
  const reacts = $derived(reactionRows(projection));
  const social = $derived(socialIndex(entries));
  const available = $derived(!channelId || visible(remoteMessage({id:'__search_channel__',channel_id:channelId,text:'',occurred_at:Number.MAX_SAFE_INTEGER})));
  const parsed = $derived(parseSearch(query));
  const parsedMessage = $derived.by(() => {
    try { return { q: query.trim() ? core.searchParse(query) : null, error: '' }; }
    catch(e) { return { q: null, error: e instanceof Error ? e.message : String(e) }; }
  });
  onMount(() => {
    index = new SearchIndex(projection); journal = new JournalIndex(projection,connection.account,() => sync.frontHistory()); revision++;
    const projectionOff=sync.subscribeProjection((p,d) => { index?.apply(p,d); journal?.apply(p,d); revision++; });
    const observeConnection=() => {
      const next={status:sync.status,session:sync.device?.session,account:sync.accountId};
      if(next.status!==connection.status || next.session!==connection.session || next.account!==connection.account) connection=next;
    };
    const statusOff=sync.subscribe(observeConnection);
    observeConnection();
    return () => {projectionOff();statusOff();};
  });
  const local = $derived.by(() => {
    void revision;
    return scope === 'messages' && parsedMessage.q ? (index?.find(parsedMessage.q, Date.now(), Number.MAX_SAFE_INTEGER) ?? [])
      .map(h => messageById(projection,h.id)).filter((m): m is MessageRow => !!m && (!channelId || m.channel_id === channelId) && visible(m)).slice(0,100) : [];
  });
  const hits = $derived.by(() => {
    const rows = current ? remote : [];
    return [...new Map([...rows,...local].filter(m => (!channelId || m.channel_id === channelId) && visible(m)).map(m => [m.id,m])).values()]
      .sort((a,b) => b.occurred_at-a.occurred_at || b.id.localeCompare(a.id));
  });
  const contexts = $derived(searchContexts(projection,hits,current ? remote : [],visible));
  const localPosts = $derived.by(() => {
    void revision;
    const ids = new Set(query.trim() ? journal?.searchPosts(parsed, Number.MAX_SAFE_INTEGER).map(h => h.id) ?? [] : []);
    return entries.filter(p => ids.has(p.id) && (scope !== 'saved' || saved.has(p.id))).slice(0,100);
  });
  const allPosts = $derived.by(() => {
    const own = new Map(localPosts.map(p => [p.id,p]));
    const remoteRows = current ? remotePosts : [];
    return [...localPosts.map(local => ({id:local.id,at:local.occurred_at,local,remote:undefined as FriendPost|undefined})),
      ...remoteRows.filter(p => !own.has(p.id) && projection.rows.post?.[p.id]?.fields.deleted_at == null && (scope !== 'saved' || saved.has(p.id)) && (!parsed.from || p.author_cards?.some(a => a.id===parsed.from || (a.display_name??a.name??'').normalize('NFKD').replace(/\p{M}/gu,'').toLocaleLowerCase().includes(parsed.from!.normalize('NFKD').replace(/\p{M}/gu,'').toLocaleLowerCase())))).map(remote => ({id:remote.id,at:remote.occurred_at,local:undefined as PostRow|undefined,remote}))]
      .sort((a,b) => b.at-a.at || b.id.localeCompare(a.id));
  });
  const chatHits = $derived.by(() => {
    if (scope !== 'chats') return [];
    return channels(projection).filter((c) => c.kind !== 'thread' && !c.archived && visible(remoteMessage({ id: '__search_chat__', channel_id: c.id, text: '', occurred_at: Number.MAX_SAFE_INTEGER })) &&
      parsed.terms.every((w) => c.name.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase().includes(w))).slice(0, 100);
  });
  const library = $derived.by(() => {
    void revision;
    if (scope !== 'media' && scope !== 'files' && scope !== 'links') return [];
    const rows = Object.keys(projection.rows.message ?? {}).map((id) => messageById(projection, id)).filter((m): m is MessageRow => !!m && visible(m));
    return libraryOf(rows, scope, parsed.terms);
  });
  const peopleList = $derived(members(projection).filter((m) => !m.deleted && !m.archived));
  const chatList = $derived(channels(projection).filter((c) => c.kind !== 'thread' && !c.archived));
  const DAY = 86_400_000;
  const dates = [['Any date', 0], ['Past day', DAY], ['Past week', 7 * DAY], ['Past month', 30 * DAY], ['Past year', 365 * DAY]] as const;
  const dateLabel = $derived(parsed.after != null ? (dates.find(([, ms]) => ms && Math.abs(Date.now() - ms - parsed.after!) < DAY * 2)?.[0] ?? 'Custom') : 'Any date');
  const pick = (key: 'from' | 'in' | 'after', value: string | null) => { query = setOperator(query, key, value); picking = null; };
  const switches = $derived.by(() => { void revision; return query.trim() ? journal?.searchSwitches(parsed) ?? [] : []; });
  function url(next?: string) {
    const params = new URLSearchParams({q: scope === 'messages' ? query.trim() : parsed.terms.map(t => `"${t}"*`).join(' '),limit:'25'});
    if (scope === 'messages') { params.set('current','true'); params.set('tz',String(-new Date().getTimezoneOffset())); if(channelId) params.set('channel_id',channelId); }
    else { if(scope === 'saved') params.set('saved','true'); if(parsed.before != null) params.set('before',String(parsed.before)); if(parsed.after != null) params.set('after',String(parsed.after)); }
    if(next) params.set('cursor',next);
    return `${apiBase()}/search/${scope === 'messages' ? 'messages' : 'posts'}?${params}`;
  }
  function valid(ctx:Request) {
    return active===ctx && ctx.projection===projection && ctx.key===key && ctx.connection===connection && ctx.revision===revision &&
      sync.status==='live' && sync.device?.session===ctx.connection.session && sync.accountId===ctx.connection.account;
  }
  async function page(ctx:Request, next: string | null, signal:AbortSignal) {
    const target=ctx.url+(next ? `&cursor=${encodeURIComponent(next)}` : '');
    const r = await apiFetch(target, {headers:{authorization:`Bearer ${ctx.connection.session ?? ''}`},signal});
    if(!r.ok) throw new Error(`Search HTTP ${r.status}`);
    const result=await r.json() as {items: Hit[] & FriendPost[]; next_cursor:string|null};
    if(!valid(ctx) || signal.aborted) return {...result,dependencies:new Map<string,MessageRow>()};
    const dependencies=ctx.scope==='messages' ? await searchAncestors(ctx.projection,async id=>{
      const r=await apiFetch(`${apiBase()}/messages/${encodeURIComponent(id)}`,{headers:{authorization:`Bearer ${ctx.connection.session ?? ''}`},signal});
      if(!r.ok) return undefined;
      return remoteMessage(await r.json());
    },new Set([...remote.map(m=>m.channel_id),...result.items.map(m=>m.channel_id),...(parsedMessage.q?index?.find(parsedMessage.q,Date.now(),Number.MAX_SAFE_INTEGER).map(m=>m.channel_id) ?? []:[])])) : new Map<string,MessageRow>();
    return {...result,dependencies};
  }
  function append(ctx:Request,result:Awaited<ReturnType<typeof page>>,more=false) {
    if(ctx.scope==='messages') {
      const items=result.items.map(m=>({...remoteMessage(m),context_before:m.context_before?remoteMessage(m.context_before):null,context_after:m.context_after?remoteMessage(m.context_after):null}));
      remote=more?[...remote,...items]:items;
    } else remotePosts=more?[...remotePosts,...result.items]:result.items;
    ancestors=result.dependencies;owner=ctx;cursor=result.next_cursor;
  }
  let controller:AbortController|null=null;
  $effect(() => {
    const p=projection,k=key,c=connection; void revision;
    remote=[]; remotePosts=[]; cursor=null; owner=null; active=null; ancestors=new Map(); error=''; loading=false;
    if(!query.trim() || scope==='switches' || LIBRARY.includes(scope) || c.status!=='live' || (scope==='messages' ? !parsedMessage.q : !parsed.terms.length)) return;
    const ctx:Request={projection:p,key:k,connection:c,revision,url:url(),scope};active=ctx;
    const abort=new AbortController();controller=abort;
    const timer=setTimeout(async()=>{
      if(!valid(ctx)) return;
      loading=true;
      try {const result=await page(ctx,null,abort.signal);if(valid(ctx) && !abort.signal.aborted) append(ctx,result);}
      catch(e){if(valid(ctx) && !abort.signal.aborted) error=e instanceof Error?e.message:String(e);}
      finally{if(valid(ctx) && !abort.signal.aborted) loading=false;}
    },250);
    return ()=>{clearTimeout(timer);abort.abort();};
  });
  async function more() {
    const ctx=active,next=cursor,abort=controller;
    if(!ctx || !next || !abort || loading || !valid(ctx)) return;
    loading=true;
    try {const result=await page(ctx,next,abort.signal);if(valid(ctx) && cursor===next && !abort.signal.aborted) append(ctx,result,true);}
    catch(e){if(valid(ctx) && !abort.signal.aborted) error=String(e);}
    finally{if(valid(ctx) && !abort.signal.aborted) loading=false;}
  }
  const lookup = (id:string) => {const m=messageById(projection,id);return m && visible(m) ? m : undefined;};
  const noop = () => {};
  const chatName = (id: string) => channels(projection).find((c) => c.id === id)?.name ?? 'Chat';
  const who = (m: MessageRow) => (m.authors ?? []).map((a) => people.get(a)?.display_name ?? people.get(a)?.name).filter(Boolean).join(' & ') || 'Someone';
  const goChat = (id: string) => { location.hash = `/chat/${id}`; onclose?.(); };
  const label = $derived(tab === 'messages' ? 'Search all chats' : `Search ${TABS.find((t) => t.id === tab)?.label ?? ''}`);
  const open = (m:MessageRow) => {if(!visible(m)) return; if(onopen) {onopen(m);return;} location.hash=`/chat/${m.channel_id}/${m.id}`;onclose?.();};
</script>

{#snippet pollBody(id:string)}
  {@const raw = (current ? remote : []).flatMap(m=>[m,m.context_before,m.context_after]).find(m=>m?.id===id) as SearchMessage|undefined}
  {@const poll = pollOf(projection,id) ?? raw?.poll}
  {#if poll}<PollCard {projection} parentId={id} {poll} member={null} remoteTally={raw?.poll_tally} remoteClosedAt={(raw as { poll_closed_at?: number | null } | undefined)?.poll_closed_at}/>{/if}
{/snippet}

<section class="search-page" aria-label={channelId ? 'Search this chat' : 'Search'}>
  <header>
    <button class="back" aria-label="Back" onclick={() => (onclose ? onclose() : history.back())}><Icon name="back" size={22} /></button>
    <label class="field"><Icon name="search" size={20} />
      <!-- svelte-ignore a11y_autofocus -->
      <input type="search" aria-label={channelId ? 'Search this chat' : label} placeholder={channelId ? `Search ${available ? chatName(channelId) : 'this chat'}` : 'Search'} bind:value={query} autofocus />
      {#if query}<button class="clear" aria-label="Clear" onclick={() => (query = '')}><Icon name="close" size={18} /></button>{/if}</label>
  </header>
  {#if !channelId}<UnderlineTabs tabs={TABS} bind:value={tab} label="Search scope" />{/if}
  {#if scope === 'messages'}
    <div class="chips" role="group" aria-label="Filters">
      <button class:on={!!parsed.from} onclick={() => (picking = 'from')}>From: {parsed.from ?? 'any member'}</button>
      {#if !channelId}<button class:on={!!parsed.in} onclick={() => (picking = 'in')}>In: {parsed.in ?? 'all chats'}</button>{/if}
      <button class:on={parsed.after != null} onclick={() => (picking = 'date')}>{dateLabel}</button>
    </div>
  {/if}
  {#if error}<p role="status">Server search unavailable. Local results are below. {error}</p>{/if}
  {#if scope==='messages' && parsedMessage.error}<p role="alert">{parsedMessage.error}</p>{/if}
  {#if !available}<EmptyState icon="lock" label="Private chat" />
  {:else if scope==='chats'}
    {#each chatHits as c (c.id)}<button class="row" onclick={() => goChat(c.id)}><span class="disc">{c.name[0]?.toUpperCase() ?? '#'}</span><span class="line"><strong>{c.kind === 'member_dm' ? c.name : `# ${c.name}`}</strong></span><Icon name="chevron-right" size={18} /></button>
    {:else}<EmptyState icon="search" label="No chats found" />{/each}
  {:else if scope==='media' || scope==='files' || scope==='links'}
    {#if scope==='media'}
      <div class="media">{#each library as item (item.id)}<div class="tile" role="button" tabindex="0" onclick={() => open(item.message)} onkeydown={(e) => e.key === 'Enter' && open(item.message)}>{#if item.attachment}<AttachmentView attachment={item.attachment} revealable={false} />{/if}</div>{/each}</div>
    {:else}
      {#each library as item (item.id)}
        <div class="row" role="button" tabindex="0" onclick={() => open(item.message)} onkeydown={(e) => e.key === 'Enter' && open(item.message)}>
          <span class="disc"><Icon name={scope === 'links' ? 'link' : 'doc'} size={20} /></span>
          <span class="line"><strong>{item.label}</strong><small>{who(item.message)} · {chatName(item.message.channel_id)} · {new Date(item.message.occurred_at).toLocaleDateString()}</small></span>
        </div>
      {/each}
    {/if}
    {#if !library.length}<EmptyState icon={scope === 'media' ? 'image' : scope === 'files' ? 'doc' : 'link'} label={scope === 'media' ? 'No media' : scope === 'files' ? 'No files' : 'No links'} />{/if}
  {:else if scope==='messages' && !query.trim()}<EmptyState icon="search" label="Search messages" />
  {:else if scope!=='messages' && !query.trim()}<EmptyState icon="search" label="Type to search" />
  {:else if scope==='messages'}
    <p class="count">{hits.length}{local.length===100 || cursor ? '+' : ''} {hits.length === 1 ? 'message' : 'messages'} found</p>
    {#each hits as hit (hit.id)}
      {@const context = contexts.get(hit.id) ?? []}
      <div class="match" role="link" tabindex="0" aria-label="Message search result" onclick={() => open(hit)} onkeydown={(e) => e.key === 'Enter' && open(hit)}>
        <p class="where">{who(hit)} · {chatName(hit.channel_id)} · {new Date(hit.occurred_at).toLocaleString()}</p>
        {#each context as m (m.id)}<div class:matched={m.id===hit.id}><Message {m} readOnly cont={false} {people} {dark} {lookup} mine={false} right={false} onreply={noop} onreplyin={noop} onquote={noop} onedit={noop} ondelete={noop} onrestore={noop} onpin={noop} onforward={noop} onselect={noop} selecting={false} selected={false} onthread={noop} reacts={reacts.get(m.id)} speaker={null} onreact={noop} {emojiById} cwAutoExpand={false} extra={pollBody}/></div>{/each}
      </div>
    {:else}<EmptyState icon="search" label="No matches" />{/each}
  {:else if scope==='switches'}
    {#each switches as sw (sw.id)}<article><p>{sw.names.join(' & ')||'Switched out'} · {new Date(sw.occurred_at).toLocaleString()}</p><p>{sw.note??''}</p></article>{:else}<EmptyState icon="search" label="No matches" />{/each}
  {:else}
    {#each allPosts as hit (hit.id)}
      {#if hit.local}<PostCard post={hit.local} {projection} {people} {dark} reactions={reacts.get(hit.id) ?? new Map()} speaker={null} {social} {saved} onreply={()=>location.hash=`/journal/post/${hit.id}`} onreact={noop}/>
      {:else if hit.remote}<FriendPostCard post={hit.remote} account="Shared post" {projection} speaker={null} speakerName="" {dark}/>{/if}
    {:else}<EmptyState icon="search" label="No matches" />{/each}
  {/if}
  {#if loading}<p class="count" role="status">Searching…</p>{/if}
  {#if cursor && current}<button class="more" disabled={loading} onclick={more}>Load more</button>{/if}
</section>
{#if picking}
  <Sheet title={picking === 'from' ? 'From' : picking === 'in' ? 'In' : 'Date'} onclose={() => (picking = null)}>
    {#if picking === 'from'}
      <button class="choice" onclick={() => pick('from', null)}>Any member{#if !parsed.from}<Icon name="check" size={18} />{/if}</button>
      {#each peopleList as m (m.id)}<button class="choice" onclick={() => pick('from', m.display_name ?? m.name)}>{m.display_name ?? m.name}{#if parsed.from === (m.display_name ?? m.name)}<Icon name="check" size={18} />{/if}</button>{/each}
    {:else if picking === 'in'}
      <button class="choice" onclick={() => pick('in', null)}>All chats{#if !parsed.in}<Icon name="check" size={18} />{/if}</button>
      {#each chatList as c (c.id)}<button class="choice" onclick={() => pick('in', c.name)}>{c.name}{#if parsed.in === c.name}<Icon name="check" size={18} />{/if}</button>{/each}
    {:else}
      {#each dates as [name, ms] (name)}<button class="choice" onclick={() => pick('after', ms ? new Date(Date.now() - ms).toISOString().slice(0, 10) : null)}>{name}{#if name === dateLabel}<Icon name="check" size={18} />{/if}</button>{/each}
    {/if}
  </Sheet>
{/if}
<style>
  .search-page { display: grid; gap: var(--s-2); align-content: start; padding: var(--s-2) var(--s-4) var(--s-4); min-width: 0; overflow: auto; }
  header { display: flex; align-items: center; gap: var(--s-1); }
  .back, .clear { flex: none; display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; }
  .clear { width: 32px; height: 32px; }
  .field { flex: 1; min-width: 0; display: flex; align-items: center; gap: var(--s-2); min-height: 44px; padding: 0 var(--s-2) 0 14px; border-radius: var(--r-lg); background: var(--surface-2); color: var(--ink-3); }
  .field input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-base); caret-color: var(--accent); }
  .field:focus-within { box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--accent) 60%, transparent); }
  .chips { display: flex; gap: var(--s-2); overflow-x: auto; padding: var(--s-1) 0; scrollbar-width: none; }
  .chips button { flex: none; min-height: 34px; padding: 0 14px; border: 1px solid var(--line); border-radius: 17px; background: none; color: var(--ink-2); font: inherit; font-size: 14px; cursor: pointer; }
  .chips button.on { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
  .count, .where { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .match { display: grid; gap: 6px; padding: 10px 0; border-bottom: 1px solid var(--line); cursor: pointer; }
  .match:hover { background: color-mix(in srgb, var(--ink) 3%, transparent); }
  .matched { background: var(--surface); border-radius: var(--r-row); padding: var(--s-1) var(--s-2); }
  .row { display: flex; align-items: center; gap: var(--s-3); width: 100%; min-height: 56px; padding: 6px 0; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .disc { flex: none; width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); font-weight: 700; }
  .line { flex: 1; min-width: 0; display: grid; }
  .line strong, .line small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .line small { color: var(--ink-3); font-size: var(--fs-xs); }
  .media { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--s-1); }
  .tile { aspect-ratio: 1; overflow: hidden; border-radius: 8px; background: var(--surface-2); cursor: pointer; }
  .tile :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .more { min-height: 44px; border: 1px solid var(--line); border-radius: var(--r-row); background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .more:disabled { opacity: .5; cursor: default; }
  .choice { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); width: 100%; min-height: 46px; padding: var(--s-2) var(--s-5); border: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-md); text-align: left; cursor: pointer; }
  .choice :global(.icon) { color: var(--accent); }
  :focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
</style>
