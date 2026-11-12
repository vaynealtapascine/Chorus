// The Settings pages and how they group in the left list (Figma F9.2-F9.4, F10.6; Android More > Settings).
export type SettingsGroup = 'You' | 'Data' | 'This computer';
export interface SettingsPage { id: string; label: string; icon: string; group: SettingsGroup }

export const SETTINGS_PAGES: SettingsPage[] = [
  { id: 'account', label: 'Account', icon: 'user', group: 'You' },
  { id: 'appearance', label: 'Appearance', icon: 'sliders', group: 'You' },
  { id: 'notifications', label: 'Notifications', icon: 'bell', group: 'You' },
  { id: 'sharing', label: 'Sharing', icon: 'shield', group: 'You' },
  { id: 'data', label: 'Your data', icon: 'copy', group: 'Data' },
  { id: 'tokens', label: 'API tokens', icon: 'globe', group: 'Data' },
  { id: 'webhooks', label: 'Webhooks', icon: 'forward', group: 'Data' },
  { id: 'trash', label: 'Trash', icon: 'delete', group: 'Data' },
  { id: 'issues', label: 'Sync issues', icon: 'target', group: 'Data' },
  { id: 'devices', label: 'Devices', icon: 'users', group: 'This computer' },
  { id: 'device', label: 'This device', icon: 'monitor', group: 'This computer' },
  { id: 'computer', label: 'This computer', icon: 'home', group: 'This computer' },
  { id: 'health', label: 'Server health', icon: 'target', group: 'This computer' },
];
/** Pages reached from a row, not listed. */
export const HIDDEN_PAGES = ['advanced'];
export const DEFAULT_PAGE = 'account';

export interface SettingsContext {
  /** a person's account has no sharing ceiling of its own to set (they follow as one member) */
  person: boolean;
  admin: boolean;
  /** this browser is on the PC that runs Chorus Home */
  home: boolean;
  /** refused ops waiting on this device: the page is listed only while there are some */
  issues?: number;
}

/** The pages that apply here, in order: Sharing is a system's, Server health an admin's, This computer a home install's. */
export function visiblePages(ctx: SettingsContext): SettingsPage[] {
  return SETTINGS_PAGES.filter((p) =>
    (p.id !== 'sharing' || !ctx.person) && (p.id !== 'health' || ctx.admin) && (p.id !== 'computer' || ctx.home) && (p.id !== 'issues' || !!ctx.issues));
}

export function groupedPages(pages: SettingsPage[]): { group: SettingsGroup; pages: SettingsPage[] }[] {
  const groups: SettingsGroup[] = ['You', 'Data', 'This computer'];
  return groups.map((group) => ({ group, pages: pages.filter((p) => p.group === group) })).filter((g) => g.pages.length);
}

/** Which page a route id asks for: none or an unknown id is the first. */
export function pageFor(id: string | undefined, pages: SettingsPage[]): string {
  if (id && (pages.some((p) => p.id === id) || HIDDEN_PAGES.includes(id))) return id;
  return DEFAULT_PAGE;
}
