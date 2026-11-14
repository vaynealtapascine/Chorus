# Chorus — decisions to revisit

Owner worksheet · created 2026-11-12 · **Draft, not implementation authorization**

Type directly into the answer blocks. Skip anything you are not ready to decide; plain-language examples are enough. The questions are prompts, not a requirement to answer everything or adopt the proposed features. “Alter” here follows your wording; Chorus currently defaults to the customizable term “member” (D-004).

[Settled decisions](DECISIONS.md) remain authoritative. This worksheet does not supersede them. Each baseline below describes a documented rule or proposal, not a fresh audit of implementation. In particular, [PluralSpec](../spec/DESIGN_NOTES.md) describes a format that the app may not fully implement yet. [Scaling](SCALING.md) is a separate unverified proposal.

## How to record an answer

Set a topic's status to **Exploring**, **Ready for review**, **Approved by owner**, **Keep current**, or **Deferred**. Leave the status Exploring while thinking aloud. Approval applies only to the wording you explicitly approve, not every question in the section.

Before implementation, an agent should carry an explicitly approved change into a new D-number in DECISIONS.md, name the decisions it amends, update the relevant specs and resolve/link any OPEN_QUESTIONS entry. Unanswered questions retain existing behavior. Do not infer permission to migrate data, deploy, or implement the entire worksheet from a filled draft.

Suggested order: describe the identity/front model (R01–R05), then its view and automation effects (R06–R08), then migration and infrastructure (R09–R12). Prioritize in R13. This is an editable agenda, not a fixed roadmap.

## Index

- R01 — Parallel fronting layers
- R02 — Alter remapping
- R03 — Custom alter relationalities
- R04 — Named blended states and composite members
- R05 — Subsystems, collectives and internal fronts
- R06 — Custom view conditions
- R07 — Scripting and automation
- R08 — Privacy across identities, relations and automation
- R09 — History, conflicts, migration and interoperability
- R10 — Encryption, recovery and client trust
- R11 — Decentralisation and portable accounts
- R12 — Scale, operations, storage and retention
- R13 — Product priorities and existing open questions

## R01 — Parallel fronting layers

**Baseline / references:** D-011, D-080, D-083–D-085; SPEC §4. Named fronts are independent; layers have independent semantics and per-purpose selections. Following and manual overrides already have defined behavior.

**Questions to consider:**

1. What does parallel mean for you: simultaneous independent fronts, several layers for one alter within a front, independent dimensions (awareness/control/location), or a combination?
2. Can one alter occupy several layers in the same front at once? Which combinations are valid? Is primary per front, per layer, optional, or multiple?
3. Does changing one layer leave the others untouched? What should Replace, Add, Remove, clear, Undo and widget taps affect?
4. How do following fronts map layers? What happens when a layer is missing, renamed or removed, or a following cycle would form?
5. Which combinations drive speaking, mentions, reading, chat availability and notifications? Should each remain independently configurable?
6. How should simultaneous offline changes and overlapping history appear? What should durations count when layers overlap?

**Example to work through:** A is speaking, B is aware, and A is also present in an internal front. Describe exactly what each screen and a follower should show.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R02 — Alter remapping

**Baseline / references:** D-036, D-042, D-053; DATA_MODEL and SYNC. Stable IDs, append-only history and restorable tombstones are the baseline. “Remapping” is deliberately not assigned a meaning here.

**Questions to consider:**

1. Do you mean an alias/display mapping, correcting mistaken attribution, matching imported records, merging duplicates, splitting a record, or different representations in different contexts?
2. Is the mapping one-to-one, many-to-one or one-to-many? Is it temporary, date-bounded, per front, per audience or account-wide?
3. Should past authorship and switches retain their original identity, display a mapped identity, or receive explicit corrections? Can readers inspect the original?
4. What happens to reactions, poll votes, mentions, links, read marks, relationships, private chats and permissions? Does a mapping confer access? Decide each explicitly.
5. How are conflicting mappings, cycles, unmapped references and offline clients handled? Can a remap be previewed and reversed?
6. For imports, what proves that two records are the same alter? Should uncertain matches always remain separate?

**Example to work through:** Two imported records refer to A; separately, A later wants to be represented as B and C. Describe how these cases differ.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R03 — Custom alter relationalities

**Baseline / references:** D-017; DATA_MODEL relationship_type / relationship. Existing schema includes named types, inverse labels, symmetry and per-relationship visibility; it does not by itself define identity equivalence.

**Questions to consider:**

1. What do duality, equivalent fragments and other terms mean in your system? Give your own definitions rather than treating the labels as universal.
2. Is each relation between two alters, among any number, or between alters and groups/states? Can multiple relations coexist?
3. Which types are symmetric, directional, reciprocal, exclusive or transitive? If A is equivalent to B and B to C, must A be equivalent to C?
4. Is a relation descriptive only, or should it affect fronting, presentation, search, authorship or eligibility? Specify each effect without assuming equivalence means shared permissions.
5. Can relations change over time, carry notes/strength/context, or be uncertain? Do old records retain the relationship in force then?
6. For cross-account relations, who may create, confirm, edit, hide or end them? Can a relation expose an otherwise hidden alter?

**Example to work through:** A and B are a duality; B and C are equivalent fragments. What may Chorus infer, and what must it never infer?

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R04 — Named blended states and composite members

**Baseline / references:** PluralSpec DESIGN_NOTES D5 already distinguishes a blend shared by individual spans from a BLEND composite member. Parts remain individuals; parts are not automatically co-authors. D-077 adopts the format; format support is not proof of app implementation.

**Questions to consider:**

1. Is a named blend its own alter, a reusable state of several alters, a front preset, a relationship, or more than one of these?
2. Does it have its own stable identity, profile, pronouns, avatar, history and journals? Can it speak by itself or jointly with its parts?
3. Can membership vary while the name stays the same? Can blends overlap, contain blends, or exist alongside their parts?
4. Is degree of blending useful? Should it be optional numeric, qualitative, or omitted from the app?
5. What happens on entering, leaving or partially changing a blend? Which front layers and follower notifications change?
6. How should search and statistics distinguish time as a blend from time as its parts without double counting?

**Example to work through:** A and B sometimes blend as X but remain A and B at other times. Describe how you would record and speak as X.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R05 — Subsystems, collectives and internal fronts

**Baseline / references:** D-092 amended by D-102; D-155. Current app semantics generally use the front exactly as switched; subsystem expansion is retained for chat alignment. PluralSpec D5 supports additional forms.

**Questions to consider:**

1. Which existing behaviors should stay, and which should expand to parts? Decide separately for cards, send-as, bubble alignment, read tracking, private chats and notifications.
2. Can one alter belong to several subsystems, or be a collective and an individual simultaneously? What nesting should be expressible?
3. How do internal fronts relate to Body and to independent named fronts? Does leading internally imply anything externally?
4. What should coherent, decohered and partially coherent mean in the UI, if these terms are useful at all?

**Example to work through:** A collective is fronting externally while two parts converse internally. Specify visible identities and actual message authors.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R06 — Custom view conditions

**Baseline / references:** D-010, D-016, D-085, D-097–D-098, D-102, D-155; DESIGN and NOTIFICATIONS. Display filtering, Read as, recorded presence and access permission are distinct today.

**Questions to consider:**

1. Where should conditions apply: member lists, chat lists, feeds, front cards, profiles, widgets, stages or saved workspaces?
2. Which inputs are allowed: selected reader, recorded front, layer, relation, custom field, blend, time, device or manual mode?
3. Which operators are needed: all/any/not, membership, comparisons, time windows, relation traversal? What should unknown or unavailable data do?
4. Are these only personal filters, or also authorization rules? A display condition alone cannot grant access or bypass follower delay.
5. Are conditions evaluated now or at the event time? Should an old message change appearance when a relation changes?
6. Are saved conditions per device, alter, account or shareable template? How are conflicting rules explained and temporarily disabled?

**Example to work through:** Show this chat when A or an explicitly equivalent fragment is in a chosen layer, except while reading as B. State whether it merely hides the chat or restricts access.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R07 — Scripting and automation

**Baseline / references:** D-019 provides API/SSE/webhooks; D-040 keeps shared core pure. An embedded scripting runtime is a new proposal, not an existing capability.

**Questions to consider:**

1. What are the first three scripts you want to write? Would saved rules and actions suffice, or is a programming language necessary?
2. Where should scripts run: this device, any active device, the home server, or a separate tool? Must they work offline or with every device asleep?
3. What triggers them: button, schedule, incoming message, switch, relation change, sync completion? Are backfills and imports excluded?
4. What may they read and write? Which actions require a preview or confirmation, especially posting, switching, changing audiences or exporting?
5. How are duplicate execution, retries, loops, conflicts and partial failures handled across devices? How can you inspect and undo their effects?
6. Can scripts access network/files/secrets? How are permissions granted, resource limits enforced, and downloaded scripts reviewed? Can encrypted data be processed only where keys exist?
7. Who authors scripts, how are versions shared, and is there a global pause plus per-script disable?

**Example to work through:** When a chosen blend becomes active, propose a front change and update a personal view exactly once, even if two devices reconnect.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R08 — Privacy across identities, relations and automation

**Baseline / references:** D-015–D-016, D-084, D-101, D-147, D-149, D-154; NOTIFICATIONS §5.

**Questions to consider:**

1. Who may see relation types, endpoints, blend membership, mappings and internal fronts? Are names and existence separately private?
2. Can different audiences see different representations? What should happen when one endpoint is hidden?
3. Should custom conditions or scripts ever affect disclosure timing? Specify how the existing ceiling and delayed reveal remain enforced.
4. What should a permission change do to pending notifications, cached views, scripts and exports? Already received copies cannot be guaranteed erased.

**Example to work through:** A follower may see X but not its parts A and B. List everything the follower should learn from profiles, alerts and history.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R09 — History, conflicts, migration and interoperability

**Baseline / references:** D-036–D-038, D-053, D-055, D-077; SYNC; PluralSpec decisions remain separate from Chorus decisions.

**Questions to consider:**

1. Which changes apply only going forward, and which reinterpret historical records? Should both original and current interpretations be available?
2. How should old clients display unfamiliar layers, relations and blends? When should writing require an upgrade?
3. What must round-trip through PluralSpec, JSONL, CSV and other-app imports? What losses require an explicit warning?
4. What backup, preview, rollback and conflict review are needed before remapping existing data?
5. Which examples must produce identical results on web, Android and server?

**Example to work through:** An old offline phone reconnects after a remap and records a switch using the old identity. Describe the desired result.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R10 — Encryption, recovery and client trust

**Baseline / references:** SCALING §4 is a proposal; D-033 still establishes no E2E. D-021, D-060, D-072, D-152–D-153 also matter.

**Questions to consider:**

1. Is the goal protecting backups, preventing routine operator reads, reducing metadata, or resisting a compromised client distributor?
2. Which data should be encrypted first, and which metadata may remain visible? How should search, notifications, exports and scripts work?
3. How should device linking, key rotation, lost devices and recovery work? What recovery burden is acceptable?
4. How should existing plaintext history and backups be handled? What privacy claim can the deployed clients actually support?

**Example to work through:** Every device is lost. Describe what recovery material you would expect to have and what the operator should be able to do.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R11 — Decentralisation and portable accounts

**Baseline / references:** SCALING §7 is a proposal; D-002, D-071, D-077, D-126 are relevant. Capacity and federation feasibility have not been validated by that proposal.

**Questions to consider:**

1. Is the priority owning your data, choosing a host, resilience, connecting independent homes, or peer-to-peer operation?
2. What comes first: account migration, remote follows, remote DMs, or shared spaces? Who is authoritative for each?
3. How should handles, existing follows, identity verification and device links survive a move?
4. Are servers allowlisted or open to federation? What happens when a remote host disappears, misbehaves or refuses a deletion?

**Example to work through:** Move an account to a friend’s home server while preserving its identity and relationships.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R12 — Scale, operations, storage and retention

**Baseline / references:** SCALING §§2–6 and 9; D-030, D-036, D-043, D-053, D-064, D-086. Performance claims in SCALING are unmeasured estimates.

**Questions to consider:**

1. What user/device count, storage budget, downtime and recovery time should we design and measure for?
2. Should backups become encrypted and offsite? Who holds recovery keys and verifies restores?
3. What quotas, suspension, abuse-handling and operator powers are acceptable?
4. May obsolete read marks/preferences be compacted, or must complete op history remain recoverable? Which data is never eligible?
5. Should cross-account views sync for offline use? What should stale or revoked information look like until reconnection?
6. At what measured bottleneck would you consider a larger VPS, database concurrency changes or sharding?

**Example to work through:** A user has years of history and several offline devices. Specify what may be cached, compacted, retained or purged.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R13 — Product priorities and existing open questions

**Baseline / references:** OPEN_QUESTIONS currently contains Q21 (typing default), Q22 (also send to parent channel), Q23 (votes by archived/deleted members). Keep answers there linked rather than creating conflicting defaults.

**Questions to consider:**

1. Which three worksheet topics matter first? Which should stay parked?
2. Which controls must be easy to reach in Basic, and which belong in Advanced? What terminology should your system customize?
3. Which features need Android/web/widget parity at launch? Which can arrive in separate slices?
4. Do you want to answer Q21–Q23 now? Record the question number with the answer; existing defaults apply until approved.

**Example to work through:** Write a short everyday workflow that the first implementation must make easier.

### Your answer

- **Status:** Exploring
- **Priority / timing:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**

  _Type here._

- **What should stay unchanged / must never happen:**
- **Open details or alternatives:**
- **Approved wording and date (leave blank until decided):**
- **Implementation handoff / resulting D-number (agent fills later):**

## R14 — Additional decision (copy this section)

- **Topic:**
- **Status:** Exploring
- **Problem or experience to support:**
- **Existing decision/spec affected, if known:**
- **Decision, in your words:**

  _Type here._

- **Concrete example and expected result:**
- **Privacy, history and offline behavior:**
- **What should stay unchanged / must never happen:**
- **Alternatives or unresolved questions:**
- **Priority / timing:**
- **Approved wording and date:**
- **Implementation handoff / resulting D-number:**
