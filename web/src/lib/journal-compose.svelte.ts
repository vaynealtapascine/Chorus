/** Hand a post to the Journal composer from anywhere (a card's Quote): the composer takes it once. */
export const journalCompose = $state<{ quote: string | null }>({ quote: null });
