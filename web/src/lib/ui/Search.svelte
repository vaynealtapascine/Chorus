<script lang="ts">
  import { onMount } from 'svelte';
  import { channels, members, messageById, customEmojis, reactions as reactionRows, type MessageRow } from '../data';
  import { JournalIndex, SearchIndex, parseSearch } from '../search';
  import { core } from '../core';
  import { sync, type Projection } from '../sync/client';
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { posts as postRows, type PostRow } from '../posts';
  import { savedPosts, socialIndex } from '../journal-social';
  import { searchView, searchContexts, remoteMessage, type SearchMessage } from '../search-view';
  import type { FriendPost } from '../friends-feed';
  import { pollOf } from '../polls';
  import PollCard from './PollCard.svelte';
  import Message from './Message.svelte';
  import PostCard from './PostCard.svelte';
  import FriendPostCard from './FriendPostCard.svelte';

  let { projection, dark = true, channelId, onclose, onopen, initialScope = 'messages' }: {
    projection: Projection; dark?: boolean; channelId?: string; onclose?: () => void; onopen?: (m:MessageRow) => void;
    initialScope?: 'messages' | 'posts' | 'saved' | 'switches';
  } = $props();
  let query = $state('');
  let tab = $state<'messages' | 'posts' | 'saved' | 'switches'>('messages');
  $effect(() => { tab = initialScope; });
  let revision = $state(0);
  let index: SearchIndex | null = null, journal: JournalIndex | null = null;
  type Hit = SearchMessage & { context_before?: MessageRow | null; context_after?: MessageRow | null };
  let remote = $state<Hit[]>([]), remotePosts = $state<FriendPost[]>([]);
  let cursor = $state<string | null>(null), loading = $state(false), error = $state('');
  let owner = $state<Projection | null>(null), request = $state('');
  const scope = $derived(channelId ? 'messages' : tab);
  const key = $derived(`${scope}:${channelId ?? ''}:${query}`);
  const visible = $derived(searchView(projection, sync.accountId));
  const people = $derived.by(() => {
    const map=new Map(members(projection).map(m=>[m.id,m]));
    if(owner===projection && request===key && sync.status==='live') for(const hit of remote) for(const m of [hit,hit.context_before,hit.context_after]) if(m && visible(m))
      for(const a of (m as SearchMessage).author_cards ?? []) if(!map.has(a.id)) map.set(a.id,{...a,name:a.name??'Someone',color:a.color??'#A09184',sigils:a.sigils??[],proxy_tags:[],archived:false,deleted:false});
    return map;
  });
  const emojiById = $derived(new Map(customEmojis(projection).map(e => [e.id,e])));
  const saved = $derived(savedPosts(projection.rows.pref ?? {},sync.accountId));
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
    index = new SearchIndex(projection); journal = new JournalIndex(projection,sync.accountId,() => sync.frontHistory()); revision++;
    return sync.subscribeProjection((p,d) => { index?.apply(p,d); journal?.apply(p,d); revision++; });
  });
  const local = $derived.by(() => {
    void revision;
    return scope === 'messages' && parsedMessage.q ? (index?.find(parsedMessage.q, Date.now(), Number.MAX_SAFE_INTEGER) ?? [])
      .map(h => messageById(projection,h.id)).filter((m): m is MessageRow => !!m && (!channelId || m.channel_id === channelId) && visible(m)).slice(0,100) : [];
  });
  const hits = $derived.by(() => {
    const rows = owner === projection && request === key && sync.status === 'live' ? remote : [];
    return [...new Map([...rows,...local].filter(m => (!channelId || m.channel_id === channelId) && visible(m)).map(m => [m.id,m])).values()]
      .sort((a,b) => b.occurred_at-a.occurred_at || b.id.localeCompare(a.id));
  });
  const contexts = $derived(searchContexts(projection,hits,owner===projection && request===key && sync.status==='live' ? remote : [],visible));
  const localPosts = $derived.by(() => {
    void revision;
    const ids = new Set(query.trim() ? journal?.searchPosts(parsed, Number.MAX_SAFE_INTEGER).map(h => h.id) ?? [] : []);
    return entries.filter(p => ids.has(p.id) && (scope !== 'saved' || saved.has(p.id))).slice(0,100);
  });
  const allPosts = $derived.by(() => {
    const own = new Map(localPosts.map(p => [p.id,p]));
    const remoteRows = owner === projection && request === key && sync.status === 'live' ? remotePosts : [];
    return [...localPosts.map(local => ({id:local.id,at:local.occurred_at,local,remote:undefined as FriendPost|undefined})),
      ...remoteRows.filter(p => !own.has(p.id) && projection.rows.post?.[p.id]?.fields.deleted_at == null && (scope !== 'saved' || saved.has(p.id)) && (!parsed.from || p.author_cards?.some(a => a.id===parsed.from || (a.display_name??a.name??'').normalize('NFKD').replace(/\p{M}/gu,'').toLocaleLowerCase().includes(parsed.from!.normalize('NFKD').replace(/\p{M}/gu,'').toLocaleLowerCase())))).map(remote => ({id:remote.id,at:remote.occurred_at,local:undefined as PostRow|undefined,remote}))]
      .sort((a,b) => b.at-a.at || b.id.localeCompare(a.id));
  });
  const switches = $derived.by(() => { void revision; return query.trim() ? journal?.searchSwitches(parsed) ?? [] : []; });
  function url(next?: string) {
    const params = new URLSearchParams({q: scope === 'messages' ? query.trim() : parsed.terms.map(t => `"${t}"*`).join(' '),limit:'25'});
    if (scope === 'messages') { params.set('current','true'); params.set('tz',String(-new Date().getTimezoneOffset())); if(channelId) params.set('channel_id',channelId); }
    else { if(scope === 'saved') params.set('saved','true'); if(parsed.before != null) params.set('before',String(parsed.before)); if(parsed.after != null) params.set('after',String(parsed.after)); }
    if(next) params.set('cursor',next);
    return `${apiBase()}/search/${scope === 'messages' ? 'messages' : 'posts'}?${params}`;
  }
  async function page(next: string | null, signal?: AbortSignal) {
    const r = await apiFetch(url(next ?? undefined), {headers:{authorization:`Bearer ${sync.device?.session ?? ''}`},signal});
    if(!r.ok) throw new Error(`Search HTTP ${r.status}`);
    return await r.json() as {items: Hit[] & FriendPost[]; next_cursor:string|null};
  }
  $effect(() => {
    const p = projection, k = key; void revision;
    remote=[]; remotePosts=[]; cursor=null; owner=null; error=''; loading=false;
    if(!query.trim() || scope === 'switches' || sync.status !== 'live' || (scope === 'messages' ? !parsedMessage.q : !parsed.terms.length)) return;
    const abort=new AbortController();
    const timer=setTimeout(async () => {
      loading=true;
      try { const result=await page(null,abort.signal); if(abort.signal.aborted) return;
        if(scope === 'messages') remote=result.items.map(m => ({...remoteMessage(m),context_before:m.context_before ? remoteMessage(m.context_before):null,context_after:m.context_after ? remoteMessage(m.context_after):null}));
        else remotePosts=result.items;
        owner=p; request=k; cursor=result.next_cursor;
      } catch(e) { if(!abort.signal.aborted) error=e instanceof Error ? e.message : String(e); }
      finally { if(!abort.signal.aborted) loading=false; }
    },250);
    return () => {clearTimeout(timer);abort.abort();};
  });
  async function more() {
    if(!cursor || loading) return;
    const p=projection,k=key,next=cursor; loading=true;
    try {const result=await page(next); if(p!==projection || k!==key || cursor!==next || sync.status!=='live') return;
      if(scope==='messages') remote=[...remote,...result.items.map(m => ({...remoteMessage(m),context_before:m.context_before ? remoteMessage(m.context_before):null,context_after:m.context_after ? remoteMessage(m.context_after):null}))]; else remotePosts=[...remotePosts,...result.items]; cursor=result.next_cursor;
    } catch(e) {if(p===projection && k===key) error=String(e);} finally {if(p===projection && k===key) loading=false;}
  }
  const lookup = (id:string) => {const m=messageById(projection,id);return m && visible(m) ? m : undefined;};
  const noop = () => {};
  const open = (m:MessageRow) => {if(!visible(m)) return; if(onopen) {onopen(m);return;} location.hash=`/chat/${m.channel_id}/${m.id}`;onclose?.();};
</script>

{#snippet pollBody(id:string)}
  {@const raw = (owner===projection && request===key && sync.status==='live' ? remote : []).flatMap(m=>[m,m.context_before,m.context_after]).find(m=>m?.id===id) as SearchMessage|undefined}
  {@const poll = pollOf(projection,id) ?? raw?.poll}
  {#if poll}<PollCard {projection} parentId={id} {poll} member={null} remoteTally={raw?.poll_tally}/>{/if}
{/snippet}

<section class="search-page" aria-label={channelId ? 'Search this chat' : 'Search'}>
  <header><h1 class="display">{channelId ? (available ? `Search ${channels(projection).find(c=>c.id===channelId)?.name ?? 'this chat'}` : 'Search this chat') : 'Search'}</h1>{#if onclose}<button onclick={onclose}>Close search</button>{/if}</header>
  {#if !channelId}<nav aria-label="Search scope">{#each [['messages','All chats'],['posts','Journal'],['saved','Saved'],['switches','Switches']] as [id,label]}<button aria-pressed={scope===id} onclick={()=>tab=id as typeof tab}>{label}</button>{/each}</nav>{/if}
  <input type="search" aria-label={channelId ? 'Search this chat' : `Search ${scope==='messages'?'all chats':scope==='saved'?'saved posts':scope==='posts'?'Journal':'switches'}`} placeholder="Search words or filters" bind:value={query}/>
  <p class="hint">{scope==='messages' ? 'Current front · from:, in:, has:, before:, after:, is:pinned. Context shows only messages available now.' : scope==='switches' ? 'Switch names and notes · before: and after:.' : `${scope==='saved'?'Bookmarked posts':'Journal posts and entries'} · titles, text and tags; before: and after:.`}</p>
  {#if sync.status!=='live'}<p class="hint" role="status">Offline · searching what is on this device. Shared posts need a connection.</p>{/if}
  {#if error}<p role="status">Server search unavailable. Local results are below. {error}</p>{/if}
  {#if scope==='messages' && parsedMessage.error}<p role="alert">{parsedMessage.error}</p>{/if}
  {#if !available}<p class="empty">This chat is unavailable to the current front.</p>
  {:else if !query.trim()}<p class="empty">Type to search {channelId?'this chat':scope==='messages'?'chats available to the current front':scope==='saved'?'your saved posts':scope==='posts'?'Journal posts and entries':'switches'}.</p>
  {:else if scope==='messages'}
    <p class="hint">{hits.length} visible matches{local.length===100?' · first 100 local matches; narrow your search':''}</p>
    {#each hits as hit (hit.id)}
      {@const context = contexts.get(hit.id) ?? []}
      <article class="match" aria-label="Message search result"><p class="hint">{channels(projection).find(c=>c.id===hit.channel_id)?.name ?? 'Chat'} · {new Date(hit.occurred_at).toLocaleString()}</p>
      {#each context as m (m.id)}<div class:matched={m.id===hit.id}>{#if m.id===hit.id}<p class="hint">Match</p>{/if}<Message {m} readOnly cont={false} {people} {dark} {lookup} mine={false} right={false} onreply={noop} onreplyin={noop} onquote={noop} onedit={noop} ondelete={noop} onrestore={noop} onpin={noop} onforward={noop} onselect={noop} selecting={false} selected={false} onthread={noop} reacts={reacts.get(m.id)} speaker={null} onreact={noop} {emojiById} cwAutoExpand={false} extra={pollBody}/></div>{/each}
      <button onclick={()=>open(hit)}>Open message</button></article>
    {:else}<p class="empty">No matching messages available to the current front.</p>{/each}
  {:else if scope==='switches'}
    {#each switches as sw (sw.id)}<article><p>{sw.names.join(' & ')||'Switched out'} · {new Date(sw.occurred_at).toLocaleString()}</p><p>{sw.note??''}</p></article>{:else}<p class="empty">No matching switches.</p>{/each}
  {:else}
    {#each allPosts as hit (hit.id)}
      {#if hit.local}<PostCard post={hit.local} {projection} {people} {dark} reactions={reacts.get(hit.id) ?? new Map()} speaker={null} {social} {saved} onreply={()=>location.hash=`/journal/post/${hit.id}`} onreact={noop}/>
      {:else if hit.remote}<FriendPostCard post={hit.remote} account="Shared post" {projection} speaker={null} speakerName="" {dark}/>{/if}
    {:else}<p class="empty">{scope==='saved'?'No matching saved posts. Save posts with their bookmark button.':'No matching Journal posts or entries.'}</p>{/each}
  {/if}
  {#if loading}<p class="hint" role="status">Searching…</p>{/if}
  {#if cursor && owner===projection && request===key && sync.status==='live'}<button disabled={loading} onclick={more}>Load more</button>{/if}
</section>
<style>
.search-page{display:grid;gap:var(--s-3);padding:var(--s-4);min-width:0;overflow:auto}header{display:flex;align-items:center;justify-content:space-between;gap:var(--s-3)}h1{margin:0;font-size:var(--fs-xl)}nav{display:flex;flex-wrap:wrap;gap:var(--s-2)}input,button{font:inherit;color:var(--ink);background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:12px 16px}button{cursor:pointer;min-height:44px}button[aria-pressed=true]{background:var(--surface-3);color:var(--accent)}button:disabled{opacity:.5;cursor:default}input{width:100%;box-sizing:border-box;caret-color:var(--accent)}.hint,.empty{margin:0;color:var(--ink-3);font-size:var(--fs-sm)}.empty{padding:var(--s-4) 0}.match{display:grid;gap:var(--s-3);border-bottom:1px solid var(--line);padding:var(--s-3) 0}.matched{background:var(--surface);border-radius:12px;padding:var(--s-2)}.match>button{justify-self:start}:focus-visible{outline:2px solid var(--accent);outline-offset:3px}
</style>
