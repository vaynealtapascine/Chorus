# Figma redesign implementation

Owner request, 2026-10-24. Source: [Chorus — Flows Copy](https://www.figma.com/design/389A1Z2QPTRpZw6d79Cz7k/Chorus--Flows--Copy).
Read `DECISIONS.md`, `FLOWS.md`, `ENTITIES.md`, `CLIENTS.md` and the Android handoffs first.

## Direction

Operate mode. Reference colours: canvas `#15161A`, panel `#1E2026`, raised `#272A31`,
line `#34373F`, foreground `#FBF3E5`, secondary `#A39E92`, teal `#42A397`, teal wash
`#1D2F30`, on-accent `#101018`. Hanken Grotesk, 15–16 px body, 20 px app titles, 12–13 px
metadata. Quiet separation, round avatar discs, 12–16 px rows and 20–22 px front containers.
Keep neutral controls; show active states with teal and text/shape as well as colour.

Dark is the default. Retain a light palette and support an explicit theme choice. Use the
existing CSS/Kotlin token generator and Material components. Downloaded Figma icons stay local.
Do not ship screenshots as UI or demo account data as real content.

## Screen map

- F3.1 `3:321`, F3.2 `3:370`: Home/fronting and switcher; nested layer presentation.
- F4.1 `3:607`: member directory as readable avatar/name/metadata rows.
- F5.2 `3:822`, F5.21 `2019:1557`: native chat and desktop columns; persistent composer.
- F6.1 `3:1141`, F6.13 `2094:5066`: profile and journal; feed navigation and quiet separators.
- F10.1 `3:1730`: More destinations; full-row targets with descriptions.

Wide desktop uses persistent navigation plus contextual list/content columns. Compact web and
Android use five main destinations, with Journal and less frequent tools reachable from More.
Person accounts continue to hide system-only front/member controls (D-003).

## Owner-approved feature gaps

- Independent ordered named fronts; optional following of another front, with overrides.
- Configurable per-system layer names and levels; the existing three levels are defaults.
- Poll author chooses single or multiple choice; each member gets one editable vote; eligibility
  comes from the parent audience. Preserve existing privacy checks for every read and mutation.

Implementation status and next steps belong in `PROGRESS.md`. Further behavior gaps require an
owner question unless existing decisions/handoffs already settle them. Nothing is deployed or
installed on the owner's phone by this task without the handoff's required device agreement.
