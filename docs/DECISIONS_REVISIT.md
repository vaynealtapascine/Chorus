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

- **Status:** Drafted

- **Priority / timing:** Low priority

- **Decision, in your words:**
  
  - For me, layers can sit *alongside* each other. For example, a "co-conscious" layer might sit next to "at the controls" custom layer. It would generally be best if it would be possible to have more "custom" options with regards to the shape of fronting.
  
  - An alter is able to occupy several layers at the same front at once. Primary should be per-front and there largely should still just be one of them. However, it should be possible to have more than one or zero "primary" front.
  
  - Changing one layer does not change other layers.
    
    - Replace refers to a front's *entire current state* and re-sets the entire front's status.
      
      - However, we should be able to lock a member at a particular layer and can not be otherwise modified, overrideable in the Switch menu with a "break front locks". If you tap a lock again, it should become a "frontstuck" status, which acts like "stronger" front locks, but it is part of the fronting status (visible to whoever the layer and member are visible to). The override switch can also be elevated to "break frontstuck".
    
    - Add can add to any existing front status.
    
    - Remove can remove any existing front status, but requires confirmation for front locks and frontstuck statuses.
    
    - Undo always works through any locks or stucks. It should always undo the last transaction and return to the prior state.
    
    - The widget should be able to cycle through fronts. Member list should show their current front layer and tapping should cycle through them. The front is registered after five seconds without interacting with the widget. A dedicated switch button pulls up the app's switch drawer.
  
  - I think individual layers can "follow" individual layers across other fronts. If one side of a map is renamed, it retains its link. If one side of a map is deleted, it should warn the  user that a following layer will lose its following status.
    
    - For cycles, following fronts calculate 8 (configurable) tentative iterations, calculating the dependent layer first then following the cycle for 7 more iterations. If 8 iterations return a stable front configuration, then stop calculating there and mark it "closed cycle". If it doesn't, then return an error, undo the status, and reject the switch.
  
  - What counts across speaking, reading, chat availability, availability, and notifications should remain configurable and customizable.
    
    - It should also be possible to "attach" viewing settings to certain people for when they front.
  
  - For offline changes, notify the user that simultaneous offline changes were recorded, but do not modify them in-place. Give the user options to apply all of them, apply only those from certain devices, or discard all conflicts in favor of the last online switch (with confirm). For overlapping durations across the same front / different layers, each entity's fronting time in a front is only recorded once.

- **Concrete example and expected result:**
  
  * Member A is fronting (primary) alongside Member B (also primary front)
    
    * All their chats and visible messages to them are both visible (this is a setting they agreed to when switching in).
    
    * Chat Xa has a setting that explicitly disallows Member A, but allows Member B. Due to the explicit exclusion, Chat Xa is not visible while they are both fronting.
    
    * Chat Yc is visible, despite both Member A and B not being there. This is because Member A's front settings includes everything visible to Member C, who is part of Chat Yc.
    
    * Member A is a subsystem, with Members Af, Ag, Ah, and Aj. Member A is fronting, so all chats visible to Af, Ah, and Aj are available by default. Member Ag is explicitly marked Sleeping (custom external front layer). Their settings also state that, if they are not fronting or co-conscious or present, then chats exclusive to them do not show up for Member A or any other subsystem members.
    
    * Member B is also a subsystem, with Members Bt, Bu, and Bv.

- **What should stay unchanged / must never happen:**
  
  - Chorus should not state "that's not a valid front state", since we're not intending to prescribe valid front states, only that it is non-standard and requires extra annotation.

- **Open details or alternatives:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - **Offline switches:** only a late switch that clashes (overlaps or contradicts something recorded meanwhile) is held out of the timeline, unseen by followers, until we choose apply all / some devices' / discard. A late switch that clashes with nothing merges by its real time as now. Amends D-037 when carried over.
    - **Subsystem parts in the example:** a per-subsystem choice, default as today (see R05).
    - *Agent's reading, to confirm:* a following cycle that could not be refused when written (two offline devices each made a valid change) puts the later change in the same held list. Front locks sync to our devices and are not part of the status; frontstuck is part of the status.

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** Drafted

- **Priority / timing:** Medium priority

- **Decision, in your words:**
  
  - A "remap" is a point in time where a member or a number of members have a break in continuity/direct lineage and new members/entities are mapped onto old members, maintaining a history of converging and diverging identities/records.
    
    - A system might undergo a "full" remap, which involves multiple members who go dormant (and are archived), multiple members who are created again (maintain lineage and history/visibility), some members who are replaced fully by others (a new member takes over lineage), some members who are replaced partially (a new member and an old member share convergent lineage, given option to attribute past records to both members or be attributed to the superceded member), and some members who are in an unknown state (retain lineage). Generally, requires *all* members to have a change.
    
    - A system might undergo a "minor" or "partial" remap, where only some members undergo a change.
    
    - A member might undergo their own localized remap, where only their submembers or only their own lineage is modified.
    
    - A temporary member/entity may optionally be appointed "maintainer", whose role is to accept all dangling references (such as a group chat that only contained one member of the system but, at remap, was mapped to no one, leaving a group chat where the account should have presence but no referrent exists).
      
      - If no maintainer is appointed, dangling references are dropped (in the example provided, the account would no longer have a presence and leave a "<Member> is no longer present" system message following removal)
  
  - Mapping is a user-customized any-to-any. It can be temporary, in the past (affected entire previous lineage), current, or future. It impacts the entire account.
  
  - Past authorship and switches *may* retain their original identity, but that is up to the user. Readers can only expect the *current state of the system* and retain only information insofar as it is specified to be in the past.
    
    - For example, Account Y might see Member A sent them a message. Member A from Account X undergoes a remap and Account X makes the decision that Member A has split into Member Aa and Member Ab and decided that all past messages and switches retain lineage to Member Aa. Account Y now sees Member Aa as having sent the message instead, since Account X made the decision to map Member A's prior lineage to the new Member Aa.
  
  - Mapping confers access, programmatically applied according to user-specified changes. The user is warned about this. Future permissions are settled with the new entities mapped rather than previous lineage.
  
  - Remap resiliency:
    
    - Conflicting mappings: common sense defaults are provided as a preview to the user. They are explicitly asked for confirmation about what changes are kept.
    
    - Cycles: they are resolved in 8 (customizable) iterations. If they are stable after 8, mark resolved. If not, reject, roll back, and inform user, showing the result of each step.
    
    - Unmapped references: The lineage is ended. The user may opt to archive them or map the referrent again in the future, reviving the lineage.
    
    - Offline remaps with conflicting changes *may be* reconciled by traversal in temporal order, resolving actions to their mapped counterparts once online. The user is given an option to do full temporal reconcile or resolve up to remap (drops all succeeding operations).
    
    - A remap can be previewed and reversed. Prior to a remap, the user is prompted to do a full account snapshot and data export, which can later be "rolled back" to.
  
  - Records are resolved through their lineage. Dates are resolved against the last prior remap and calculated from there until the next remap. Uncertain matches should remain separate.

- **Concrete example and expected result:**
  
  _Type here._

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - **Rolling back:** reverses the mapping only. A new entry cancels the remap; everything written since is kept and reads through the earlier lineage again. The snapshot is a safety export, not something the app restores from.
    - **Members that converge:** the remap preview lists each remapped pair or group and asks whether that group's votes and reactions are merged (counted once) or split-counted.
    - *Agent's reading, to confirm:* where the answer above says later operations are dropped, they are set aside in Trash and can be restored (the never-lose-data rule); a remap never notifies followers by itself, and what a follower sees of a fronting member changes only at a reveal.

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** Drafting

- **Priority / timing:**

- **Decision, in your words:**
  
  - Terminology in our system specifically:
    
    - Duality: The members are two halves of the same whole and must have approximately the same access as each other. One is automatically able to act in the same spaces as the other. Only one is visible at any given moment. However, one half of a duality may be excluded from spaces or from visibility. In a sense, one *comes with* the other.
    
    - Equivalent: The members are fully equivalent and may only differ in appearance or manifestation. One can not be excluded without the other. In a sense, one *is* the other.
    
    - Overseer: The member is able to *see* (but not necessarily *act in*) the same spaces as the other. They are explicitly listed alongside them as an overseer. In a sense, one *looks over* the other, but can not *act* as the other.
  
  - A relation may be between two alters, any number of them, or exist between groups and states. Multiple relations can coexist. They are resolved through user-specified priority.
  
  - Custom relationalities must be able to specify if they are transitive, symmetric, directional, reciprocated, exclusive, etc.
  
  - A relation is functional. All of these must be user-customizable.
  
  - Relations can change over time, carry notes and context, or be uncertain. They may be declared publicly or privately. Changes can be technical (destructive change) or semantic (recorded in history). Changes can be broadcasted (listed in journal) with specified visibility.
  
  - Relations are intended to be an intra-account feature for functionality and scope control.

- **Concrete example and expected result:**
  
  _Type here._

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - **Enforcement:** relations are hard rules. They change real access (who can see and speak where) and the server checks them.
    - **"Only one is visible":** a per-relation setting: a switch on the relation type with a choice of scope (fronts, our lists, other accounts).
    - *Agent's reading, to confirm:* a relation marked uncertain is descriptive only and has no effects until confirmed.

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** Drafting

- **Priority / timing:** Low priority

- **Decision, in your words:**
  
  - A named blend can be its own altar, a reusable state, or a front preset. It is explicitly not a relationship or relation.
  
  - It can have its own identity and perform its own actions. It can not perform actions alongside its constituent parts.
  
  - Yes, membership can shift. Blends can overlap and contain other blends, but may not exist or act alongside its constituent parts.
  
  - Degree of blending can be specified as a note or status. It does not need explicit app support.
  
  - Blend notifications can either widen (include visibility of all members) or narrow (only shared visibility across all members), specified per blend. Notifications function similarly, either loosening (accepting the most private/lenient option) or tightening (accepting only the weakest option).
  
  - Each member is granted time as a fraction of their part of the blend. This means blends can optionally specify dominance or fractional part, but otherwise defaults to an even split.

- **Concrete example and expected result:**
  
  _Type here._

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - **A blend switching in beside one of its parts:** ask each time ("Take A and B out?"), with a "don't ask for this blend again" option. Nothing is refused.
    - **What a friend learns** when they may see X but not its parts: X looks like any member (see R08).

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

## R05 — Subsystems, collectives and internal fronts

**Baseline / references:** D-092 amended by D-102; D-155. Current app semantics generally use the front exactly as switched; subsystem expansion is retained for chat alignment. PluralSpec D5 supports additional forms.

**Questions to consider:**

1. Which existing behaviors should stay, and which should expand to parts? Decide separately for cards, send-as, bubble alignment, read tracking, private chats and notifications.
2. Can one alter belong to several subsystems, or be a collective and an individual simultaneously? What nesting should be expressible?
3. How do internal fronts relate to Body and to independent named fronts? Does leading internally imply anything externally?
4. What should coherent, decohered and partially coherent mean in the UI, if these terms are useful at all?

**Example to work through:** A collective is fronting externally while two parts converse internally. Specify visible identities and actual message authors.

### Your answer

- **Status:** Drafting

- **Priority / timing:** Medium priority

- **Decision, in your words:**
  
  - I think what's currently going on is fine?
    
    - An subsytem alter's profile should probably list all the posts of itself and its members though.
  
  - An alter can exist in several subsystems and be a collective and an individual simultaneously (see current subsystems). Any reasonable nesting should be possible. Any cycles should resolve or be stable in 8 (user-specified) iterations or less.
  
  - I mean, i think how they currently work with subsystems work fine?
  
  - I think this just represents me wanting to give members a transient 100-character status field that specifies how they're doing. A bit like an AIM status?

- **Concrete example and expected result:**
  
  _Type here._

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - **Parts' chats and speakers:** a per-subsystem choice, default as today (D-102, D-155). Containment becomes a built-in relation with switchable effects; nothing changes until a subsystem turns one on.
    - *Agent's reading, to confirm:* the status is the member state note that already exists (D-122, D-138: up to 200 characters, clears on leaving, has an audience), shown as one line beside the name; the new part is where it shows, not a new record.

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** DDrafting

- **Priority / timing:** Low priority

- **Decision, in your words:**
  
  - Possible condition filters apply to:
    
    - member lists, chat lists, journal feeds, profiles, widgets.
  
  - Possible inputs are: selected reader (see as), current front, layer fronters, relations, custom fields, manually-specified views.
  
  - All operators are needed. If ambiguous, provide options for both loosening or tightening fail paths.
  
  - These are intended to mostly be personal intrasystem filters, but can interact with other accounts.
  
  - View mode conditions can be retroactive (calculated according to lineage and present rules on view) or permanent (calculated at action time and locked, with only postfacto lineage changes calculated afterwards).
  
  - Saved conditions are per account. Conflicting rules are ideally explained in simple, predictable natural language to the user. However, view rules are calculated as a priority list, done in order from top to bottom.

- **Concrete example and expected result:**
  
  _Type here._

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - **Enforcement:** view conditions are soft. They only arrange our own screens and can never grant or remove access.

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** Deferred

- **Priority / timing:** Very low priority

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - Parked entirely: no automation work in this plan.
    - If it is ever taken up: rules run on one device we choose (they sleep while it is offline), not on the server.

- **Concrete example and expected result:**
  
  Not worked through (parked).

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

## R08 — Privacy across identities, relations and automation

**Baseline / references:** D-015–D-016, D-084, D-101, D-147, D-149, D-154; NOTIFICATIONS §5.

**Questions to consider:**

1. Who may see relation types, endpoints, blend membership, mappings and internal fronts? Are names and existence separately private?
2. Can different audiences see different representations? What should happen when one endpoint is hidden?
3. Should custom conditions or scripts ever affect disclosure timing? Specify how the existing ceiling and delayed reveal remain enforced.
4. What should a permission change do to pending notifications, cached views, scripts and exports? Already received copies cannot be guaranteed erased.

**Example to work through:** A follower may see X but not its parts A and B. List everything the follower should learn from profiles, alerts and history.

### Your answer

- **Status:** Ready for review

- **Priority / timing:** Travels with each identity slice (R01–R06)

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - A friend who may see blend X but not its parts sees X like any member: no sign that it is a blend, no part count, no "with someone".
    - Relations and lineage with a hidden end are not shown at all. Sharing more is a per-blend, per-relation audience we set.
    - Relations are hard rules and view conditions are soft (R03, R06), so a view condition never changes what anyone else can learn.
    - *Agent's reading of the existing invariant (NOTIFICATIONS §5), not a new choice:* nothing new may change for a follower at the real time of a switch. A remap of someone fronting, a frontstuck mark, a duality's shown half and a blend's widened audience all reach a follower only at a reveal under the ceiling. Everything new starts as "only us".

- **Concrete example and expected result:**
  
  A follower may see X but not A and B. Profile: X's own shared parts only. Alerts: "X is fronting" at the reveal time. History: X's revealed intervals. Nothing names A or B, counts them, or says X is a blend.

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** Ready for review

- **Priority / timing:** Travels with each identity slice (R01–R06)

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - An out-of-date device is read-only until updated: once an account turns on any new-model feature, a device below the needed version can read what it understands but cannot switch or post.
    - Rolling back a remap reverses the mapping only (R02).
    - When members converge, each remapped pair or group chooses merged or split-counted votes and reactions in the preview (R02).
    - Late offline switches that clash are held; the others merge as now (R01).
    - *Not asked; agent's proposal in the plan:* which records round-trip through PluralSpec, JSONL and CSV, and which fixtures must match on web, Android and server.

- **Concrete example and expected result:**
  
  An old offline phone reconnects after a remap holding a switch recorded as A. It cannot push until it updates. Once updated, the switch is read through the lineage: if the remap gave A one heir it is recorded as that heir and marked "recorded as A before the remap"; if A was split with no single heir, or its lineage ended, the switch is held for us to place.

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

## R10 — Encryption, recovery and client trust

**Baseline / references:** SCALING §4 is a proposal; D-033 still establishes no E2E. D-021, D-060, D-072, D-152–D-153 also matter.

**Questions to consider:**

1. Is the goal protecting backups, preventing routine operator reads, reducing metadata, or resisting a compromised client distributor?
2. Which data should be encrypted first, and which metadata may remain visible? How should search, notifications, exports and scripts work?
3. How should device linking, key rotation, lost devices and recovery work? What recovery burden is acceptable?
4. How should existing plaintext history and backups be handled? What privacy claim can the deployed clients actually support?

**Example to work through:** Every device is lost. Describe what recovery material you would expect to have and what the operator should be able to do.

### Your answer

- **Status:** Ready for review

- **Priority / timing:** Second track, after the server groundwork and before the identity work

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - Goal: the operator cannot read content. Devices encrypt text, names, bios, field values and files; ids, times and permissions stay plain so the server still enforces the privacy rules. Reverses D-033.
    - Server-side search, server-written notification text and server exports move to the devices.
    - Recovery: a recovery key we keep (required at setup, no escrow), plus optionally a recovery share split among friends we choose.
    - Existing plain history is sealed in place: a device re-uploads each old record's content encrypted and the server swaps the stored text for it, keeping ids and order. Old plain backups are deleted once a sealed backup is verified. This is the one deliberate exception to "records are never rewritten".

- **Concrete example and expected result:**
  
  Every device is lost. With the recovery key, or with enough of the friends who hold a share approving, a new device gets everything back. With neither, the content is gone and the operator cannot help. The operator can still see who talks to whom and when, sizes, and switch times against opaque member ids.

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

## R11 — Decentralisation and portable accounts

**Baseline / references:** SCALING §7 is a proposal; D-002, D-071, D-077, D-126 are relevant. Capacity and federation feasibility have not been validated by that proposal.

**Questions to consider:**

1. Is the priority owning your data, choosing a host, resilience, connecting independent homes, or peer-to-peer operation?
2. What comes first: account migration, remote follows, remote DMs, or shared spaces? Who is authoritative for each?
3. How should handles, existing follows, identity verification and device links survive a move?
4. Are servers allowlisted or open to federation? What happens when a remote host disappears, misbehaves or refuses a deletion?

**Example to work through:** Move an account to a friend’s home server while preserving its identity and relationships.

### Your answer

- **Status:** Ready for review

- **Priority / timing:** Fourth track, after encryption and the identity work

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - Scope: everything, including shared spaces that span servers.
    - Which servers may connect: an allowlist per account. The server has its list of peered servers, and each account chooses which of them may see or follow it.
    - A space whose host is gone for good or cut off: every member keeps a read-only mirrored copy, and a space admin on a surviving server can move the space there from that mirror; other members' servers follow the signed move.

- **Concrete example and expected result:**
  
  Not worked through in the interview. The plan's first federation step is moving an account to a friend's server keeping its ids, history and follows.

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

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

- **Status:** Ready for review

- **Priority / timing:** First track

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - Design and load-test for a few hundred accounts on the current single VPS with SQLite: fix the six server hot paths in SCALING §2, add metrics and a many-devices load test, per-account file quotas and a disk alarm.
    - Backups: offsite, encrypted again with a key only the owner holds, with a monthly automatic restore check. Supersedes D-043.
    - Compaction: superseded read marks and preference changes older than 90 days move to a cold archive that is backed up and can be replayed. Switches, messages, posts, lineage and relations are never eligible.
    - *Not asked; agent's proposals in the plan:* operator powers (suspension, quotas), keeping friends' views on devices for offline use, and the measured point at which to consider a larger VPS.

- **Concrete example and expected result:**
  
  A user with years of history and several offline devices: their own data is cached on every device as now; read marks and preference changes older than 90 days that a later one replaced leave the live log for the archive; nothing else is compacted or purged.

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts.

## R13 — Product priorities and existing open questions

**Baseline / references:** OPEN_QUESTIONS currently contains Q21 (typing default), Q22 (also send to parent channel), Q23 (votes by archived/deleted members). Keep answers there linked rather than creating conflicting defaults.

**Questions to consider:**

1. Which three worksheet topics matter first? Which should stay parked?
2. Which controls must be easy to reach in Basic, and which belong in Advanced? What terminology should your system customize?
3. Which features need Android/web/widget parity at launch? Which can arrive in separate slices?
4. Do you want to answer Q21–Q23 now? Record the question number with the answer; existing defaults apply until approved.

**Example to work through:** Write a short everyday workflow that the first implementation must make easier.

### Your answer

- **Status:** Ready for review

- **Priority / timing:** —

- **Decision, in your words:**
  
  - Interview 2026-11-14 (the owner chose each answer; recorded by claude-fable-5.2):
    - Track order: server groundwork (R12), then encryption (R10), then the identity model (R01–R06, with R08 and R09), then federation (R11). Automation (R07) is parked.
    - Inside the identity work: small wins first (per-subsystem "parts come with it" switches, a subsystem's profile listing its parts' posts, the member status line beside names), then remap, then relations, then blends, layers and views.
    - Platform order: Android first, then web.
    - Q21, Q22 and Q23: all three defaults confirmed (D-161): typing is shown to other accounts only after opting in; "Also send to" posts two messages; a deleted or archived member's poll vote keeps counting.
    - *Not asked:* Basic or Advanced (AGENTS.md: when unsure, Advanced), terminology, and an everyday workflow for the first slice.

- **Concrete example and expected result:**
  
  _Type here._

- **What should stay unchanged / must never happen:**

- **Open details or alternatives:**

- **Approved wording and date (leave blank until decided):**

- **Implementation handoff / resulting D-number (agent fills later):**
  
  Plan: [docs/handoff/revisit-plan.md](handoff/revisit-plan.md). No D-number yet: each is written and approved when its phase starts. Q21–Q23 are D-161.

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