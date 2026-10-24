# Chorus

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users and purpose

Chorus serves plural systems and the people they connect with. The owner uses a native Android
client and an installed desktop PWA to track fronting, chat as members, keep journals and share
under explicit privacy ceilings. Product authority: `docs/DECISIONS.md`, then the spec set listed
in `AGENTS.md`. This record summarizes that authority; it does not replace it.

## Operating context

Offline-first local replicas sync through a self-hosted Rust server. Never lose user data;
deletion is a restorable tombstone. Shared rules belong in pure `chorus-core` and flow through
wasm and UniFFI. Follow `docs/handoff/` for platform constraints and existing work.

## Brand commitments

Use the owner's Figma Chorus — Flows Copy (`389A1Z2QPTRpZw6d79Cz7k`) as the UI guide.
Dark is the default (owner, 2026-10-24). Keep copy short, quiet and concrete. Members supply
individual colour; chrome supports the content. Basic stays small; additional controls live
in Advanced.

## Capabilities and constraints

Preserve the capabilities in `docs/FLOWS.md` and `docs/ENTITIES.md`. Owner decisions on
2026-10-24 extend fronting to independent named fronts with optional follow mode, configurable
layers defaulting to front/co-conscious/present, and polls with one editable vote per member,
single/multiple choice selected by the author, within the parent post/channel audience.
Ask the owner about material decisions absent from these briefs.

## Accessibility

WCAG AA contrast, keyboard access and visible focus on web; 48 dp Android touch targets,
TalkBack labels, OS text scaling, system Back and window/keyboard insets. Respect reduced motion.
