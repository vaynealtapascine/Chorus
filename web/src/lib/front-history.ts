import type { EffectiveFrontHistory, FoldedFrontSystem } from './core/fronts';
import type { Projection } from './sync/client';

export interface FrontHistoryReader {
  frontSystemHistory(accountId: string): string;
  effectiveFrontHistory(accountId: string, frontId: string): string;
}

export interface NamedHistory {
  saved: FoldedFrontSystem['histories'][string];
  effective: EffectiveFrontHistory;
  reviews: FoldedFrontSystem['reviews'][string];
}

/** On-demand history pages share one account fold. A front delta supplies a new dependency
 * object even when a past correction leaves today's presence unchanged. Message/member deltas
 * retain that object, so typing, renaming and chat traffic do not trigger a history fold. */
export class FrontHistoryCache {
  private token: unknown;
  private account = '';
  private reader: FrontHistoryReader | null = null;
  private own: FoldedFrontSystem | null = null;
  private effective = new Map<string, EffectiveFrontHistory>();

  read(reader: FrontHistoryReader, projection: Projection, account: string, front = account): NamedHistory | null {
    const token = projection.front_systems?.[account];
    if (token !== this.token || account !== this.account || reader !== this.reader) {
      this.token = token;
      this.account = account;
      this.reader = reader;
      this.own = null;
      this.effective.clear();
    }
    if (!token?.system.fronts[front]) return null;
    this.own ??= JSON.parse(reader.frontSystemHistory(account)) as FoldedFrontSystem;
    let effective = this.effective.get(front);
    if (!effective) {
      effective = JSON.parse(reader.effectiveFrontHistory(account, front)) as EffectiveFrontHistory;
      this.effective.set(front, effective);
    }
    return {
      saved: this.own.histories[front] ?? { switches: [], intervals: [], current: [] },
      effective,
      reviews: this.own.reviews[front] ?? [],
    };
  }
}
