export interface Member {
  id: string;
  name: string;
  pronouns?: string;
  color: string;
  sigil?: string;
  avatarBlob?: string;
}

export interface FrontEntry {
  id: string;
  level: string;
  primary: boolean;
}
