import names from 'virtual:chorus-icons';

/** Kept so the browser keeps them decoded. */
const warm: HTMLImageElement[] = [];

/**
 * Load every icon once while the page is idle: an icon drawn for the first time (a sent tick
 * turning into a read tick, a menu opening) would otherwise blink empty while it loads.
 */
export function warmIcons(): void {
  const idle = window.requestIdleCallback ?? ((f: () => void) => setTimeout(f, 200));
  idle(() => {
    for (const name of names) {
      const img = new Image();
      img.src = `/icons/${name}.svg`;
      img.decode().catch(() => {});
      warm.push(img);
    }
  });
}
