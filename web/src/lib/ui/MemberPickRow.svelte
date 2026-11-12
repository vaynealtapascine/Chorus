<script lang="ts">
  // A member you tick to put in something (a group, a subsystem): face, name, a check.
  import { core } from '../core';
  import type { MemberRow } from '../data';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';

  let { member, on, dark = false, disabled = false, sub, onclick }: {
    member: MemberRow; on: boolean; dark?: boolean; disabled?: boolean; sub?: string | null; onclick: () => void;
  } = $props();
  const c = $derived(core.adaptColor(member.color, dark));
</script>

<button class="pick" role="checkbox" aria-checked={on} {disabled} {onclick} style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}">
  <span class="avatar" aria-hidden="true"><AvatarImage hash={member.avatar_blob} glyph={member.sigils[0] ?? member.name[0]} name={member.name} /></span>
  <span class="text"><span class="name">{member.display_name ?? member.name}</span>{#if sub}<span class="sub">{sub}</span>{/if}</span>
  <span class="check" class:on aria-hidden="true">{#if on}<Icon name="check" size={16} />{/if}</span>
</button>

<style>
  .pick { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 52px; padding: 6px 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .pick:disabled { cursor: default; }
  .pick:disabled .name { color: var(--ink-3); }
  .avatar { width: 36px; height: 36px; display: grid; place-items: center; flex: none; border-radius: 50%; overflow: hidden; background: var(--ring); color: var(--avatar-ink); font-weight: 700; }
  .text { flex: 1; min-width: 0; display: grid; }
  .name { font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sub { color: var(--ink-3); font-size: 12px; }
  .check { width: 22px; height: 22px; display: grid; place-items: center; border: 2px solid var(--line); border-radius: 50%; color: var(--on-accent); flex: none; }
  .check.on { background: var(--accent); border-color: var(--accent); }
  .pick:disabled .check { opacity: .4; }
</style>
