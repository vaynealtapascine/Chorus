import { describe, expect, it } from 'vitest';
import { linkTo, linksIn, parseLink } from './share-links';

const HOME = 'https://chorus.example.org';
const POST = '01a19868-18cf-7213-980c-e6f963e216e6';
const MSG = '01a19952-112e-7059-8b64-eaa54a247db5';
const CHANNEL = '01a195d4-06da-7601-9359-eb1d2c25c114';

describe('links to posts and messages', () => {
  it('shares a short link on this server', () => {
    expect(linkTo(HOME, { kind: 'post', id: POST })).toBe(`${HOME}/post/${POST}`);
    expect(linkTo(`${HOME}/`, { kind: 'message', id: MSG })).toBe(`${HOME}/message/${MSG}`);
  });

  it('reads its own links and the app addresses a browser shows, on this server only', () => {
    expect(parseLink(`${HOME}/post/${POST}`, HOME)).toEqual({ kind: 'post', id: POST });
    expect(parseLink(`${HOME}/message/${MSG.toUpperCase()}`, HOME)).toEqual({ kind: 'message', id: MSG });
    expect(parseLink(`${HOME}/#/journal/post/${POST}`, HOME)).toEqual({ kind: 'post', id: POST });
    expect(parseLink(`${HOME}/#/chat/${CHANNEL}/${MSG}`, HOME)).toEqual({ kind: 'message', id: MSG });
    expect(parseLink(`https://elsewhere.example/post/${POST}`, HOME)).toBeNull();
    expect(parseLink(`${HOME}/post/not-an-id`, HOME)).toBeNull();
    expect(parseLink(`${HOME}/#/chat/${CHANNEL}`, HOME)).toBeNull();
    expect(parseLink('not a url at all', 'not an origin')).toBeNull();
  });

  it('finds each linked post or message once, in order, from url and text_link entities', () => {
    const text = `see ${HOME}/post/${POST} and this and ${HOME}/post/${POST} again https://x.org`;
    const first = text.indexOf(HOME);
    const second = text.lastIndexOf(`${HOME}/post`);
    const entities = [
      { type: 'url', offset: first, length: `${HOME}/post/${POST}`.length },
      { type: 'text_link', offset: text.indexOf('this'), length: 4, url: `${HOME}/message/${MSG}` },
      { type: 'url', offset: second, length: `${HOME}/post/${POST}`.length },
      { type: 'url', offset: text.indexOf('https://x.org'), length: 13 },
      { type: 'bold', offset: 0, length: 3 },
    ];
    expect(linksIn(text, entities, HOME)).toEqual([{ kind: 'post', id: POST }, { kind: 'message', id: MSG }]);
  });
});
