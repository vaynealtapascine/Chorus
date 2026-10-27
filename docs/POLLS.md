# Polls (UI3, D-081, D-086)

Implementation brief for polls on journal posts and chat messages. Behaviour is settled by D-081;
the transport choice is D-086. Nothing here changes what users see beyond D-081.

## Settled behaviour (D-081)

- The author chooses single or multiple choice when creating the poll.
- Each member has one editable vote (a member may change or withdraw it).
- The parent post's or channel's audience decides who may vote; it is the same check as reading
  the parent.
- Eligible readers see totals immediately.
- Voter identities are hidden from other accounts. A system may read and edit its own members'
  votes, privately within its account. Identities must not be synced to other accounts and then
  hidden in the UI.

## Data

**Poll definition.** An optional `poll` field on `post.create` and `message.send` (and their
forwards/imports, which copy it as-is):

```jsonc
"poll": {
  "question": "Dinner?",                 // optional; the parent text is usually the question
  "options": [{"id": "a", "text": "Soup"}, {"id": "b", "text": "Rice"}],  // 2–20, unique ids
  "multiple": false
}
```

Options and `multiple` are fixed at creation. Edits to the parent (`post.edit`, `message.edit`)
do not change the poll, so a vote always refers to the options the voter saw. Option IDs are
short strings chosen by the client (`a`, `b`, ...), unique within the poll; text is 1–200 chars.

**Votes: `poll.vote`, account-scoped.** `scope = account:<voter account>`, `entity_id` = the
parent post/message ID, payload `{member_id, choices: [option id, ...]}`. `[]` withdraws the vote.
One LWW register per `(poll, member)`: the latest vote by HLC wins. Because the op lives in the
voter's account scope, it reaches only that account's devices. The server validates on ingest:

- the member belongs to the voter's account and is not deleted;
- the voter's account may read the parent now (message: channel `view` + space membership;
  post: the post's visibility for that account);
- each choice is an option ID of that poll, with no duplicates, and at most one when
  `multiple` is false.

A vote for a parent the server has not seen yet is held like other late-parent ops and validated
when the parent arrives; it never counts before then.

**Totals: `message.poll_tally` / `post.poll_tally`, server-derived (D-086).** After accepting or rejecting the effect of a
vote, or when the parent is deleted/restored, the server recomputes the poll's totals and appends
a server-authored tally op **in the parent's scope**: `message.poll_tally` in the space for messages,
`post.poll_tally` in the author's account for posts (op kinds have one fixed scope kind). `entity_id` = parent ID, payload
`{channel_id?, counts: {"a": 3, "b": 1}, voters: 4}` (`voters` = members with a non-empty vote).
The op carries `account_id` = the parent's author and `device_id` = the server device, like other
`server_op`s, but clients may never push it (ingest refuses tallies from any device but the server). Projection
keeps one `poll_tally` row per parent, LWW by HLC; the latest tally is the total. Tally ops carry
no member or account IDs.

Why ops (D-086): totals then reuse catch-up, digests, offline caching, rebuild and both clients'
projector with no new frame. The `views` transport in SYNC.md §4.3 is not implemented yet, and a
REST-only total would be blank offline. Tally ops are derived data: they are excluded from
account exports (`ops.jsonl` contains only authored ops, as today) and from PluralSpec. A
rebuild replays them; the latest still wins.

Followers reading another account's post through REST views get the latest tally in the post row.
Message tallies follow the message's channel visibility because they carry `channel_id` and use
the same space-scope delivery filter as the message.

## Privacy

- Nothing that leaves the voter's account names the voter: `poll.vote` stays account-scoped,
  and tallies have counts only.
- Small polls can reveal votes through arithmetic (one voter, one count); that is inherent to
  "totals immediately" (D-081) and is accepted.
- Tallies are appended when the vote is accepted, so their timing is visible to readers, the same
  as a reaction's. Do not add per-vote metadata (time, device, member) to tally payloads.
- A voter who loses access to the parent keeps its own vote op; the server stops counting it at
  the next recompute (counts include only votes whose account can currently read the parent).

## Clients

- Composer: an Advanced "Add poll" disclosure on the post composer and the chat composer (options
  list, single/multiple toggle). Basic shows no poll controls (DESIGN.md §6).
- Display: question, options with counts and proportion bars, total voters, and the acting
  member's own choices highlighted. Own-account vote rows are read locally; other members' votes
  in the same system are visible to that system only.
- Voting acts as the current speaker/acting member (the same member reactions use). Changing a
  choice writes a new `poll.vote`; "Remove my vote" writes `choices: []`.
- Offline: own votes apply locally at once; totals update when the next tally arrives. Show the
  local member's choice immediately even if the total has not caught up.

## Slices

1. **Done (2026-10-27).** Core `chorus_core::poll` (definition, choice shape, `check_vote`, `tally`,
   `tally_payload`, `check_tally`), catalogue `poll.vote` / `message.poll_tally` / `post.poll_tally`,
   validation of new parents and votes (edits may not carry `poll`), reference-model rows
   `poll_vote` (`<poll>|<member>`) and `poll_tally`, migration 0015 (`message.poll`, `post.poll`,
   `poll_vote`, `poll_tally`), server projection, and ingest refusing device-pushed tallies.
   Conformance fixtures are still to add with slice 2's server behaviour.
2. Server: ingest validation (eligibility, late parents), tally recompute + tally op append,
   refuse client-pushed tallies, SQL tables/migration, post view rows, export exclusion, tests
   for identity leakage (REST, sync, SSE, exports).
3. Web, then Android: composer disclosure, poll card, voting, tests.
