// Typed wrapper over the chorus-core wasm module (crates/chorus-wasm).
// Build the module with `pwsh scripts/build-web-core.ps1`; it lands in ./pkg (git-ignored).
import init, * as wasm from './pkg/chorus_wasm.js';

export type Json = unknown;

export interface Entity {
  type: string;
  offset: number;
  length: number;
  [k: string]: unknown;
}
export interface Rich {
  text: string;
  entities: Entity[];
}
export interface Segment {
  offset: number;
  length: number;
  authors: string[];
}
export interface Composed {
  rich: Rich;
  segments: Segment[];
  authors: string[];
  explicit: boolean;
}
export interface MemberColors {
  name: string;
  ring: string;
  tint: string;
}

let ready: Promise<void> | null = null;

/** Load the wasm module once. In Node tests pass the bytes. */
export function loadCore(bytes?: BufferSource): Promise<void> {
  if (!ready) ready = (bytes ? init({ module_or_path: bytes }) : init()).then(() => undefined);
  return ready;
}

export const core = {
  version: (): string => wasm.coreVersion(),
  parseMarkup: (src: string, names: Json = {}): Rich => JSON.parse(wasm.parseMarkup(src, JSON.stringify(names))),
  toMarkup: (rich: Rich): string => wasm.toMarkup(JSON.stringify(rich)),
  compose: (src: string, speakers: Json, options: Json, defaults: string[], names: Json = {}): Composed =>
    JSON.parse(
      wasm.compose(src, JSON.stringify(speakers), JSON.stringify(options), JSON.stringify(defaults), JSON.stringify(names)),
    ),
  foldFront: (ops: Json[]): Json => JSON.parse(wasm.foldFront(JSON.stringify(ops))),
  frontDaily: (intervals: Json, now: number, offsets: [number, number][]): Json =>
    JSON.parse(wasm.frontDaily(JSON.stringify(intervals), now, JSON.stringify(offsets))),
  feedParse: (q: string): Json => JSON.parse(wasm.feedParse(q)),
  feedFilter: (ast: Json, items: Json[], context: Json): number[] =>
    JSON.parse(wasm.feedFilter(JSON.stringify(ast), JSON.stringify(items), JSON.stringify(context))),
  adaptColor: (color: string, dark: boolean, intensity: 'off' | 'subtle' | 'vivid' = 'subtle'): MemberColors =>
    JSON.parse(wasm.adaptColor(color, dark, intensity)),
  hlcTick: (last: string, node: number, now = Date.now()): string => wasm.hlcTick(last, node, now),
  hlcObserve: (last: string, node: number, remote: string, now = Date.now()): string =>
    wasm.hlcObserve(last, node, remote, now),
};
