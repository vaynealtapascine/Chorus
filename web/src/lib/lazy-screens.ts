// The screens kept out of the script that opens the app (SPEC §9, D-156): each is its own file,
// fetched when first shown (ui/Lazy.svelte) or, sooner, once the page is idle.
export const screens = {
  insights: () => import('./ui/Insights.svelte'),
  postStage: () => import('./ui/PostStage.svelte'),
  settings: () => import('./ui/SettingsShell.svelte'),
  stage: () => import('./ui/Stage.svelte'),
};

/**
 * Fetch every lazy screen while the page is idle. After this an open page no longer depends on the
 * files of the version it was loaded from, which a deploy removes from the server and the cache.
 */
export function warmScreens(): void {
  const idle = window.requestIdleCallback ?? ((f: () => void) => setTimeout(f, 200));
  idle(() => { for (const load of Object.values(screens)) load().catch(() => {}); });
}
