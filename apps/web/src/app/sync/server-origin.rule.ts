import { parseAccessContract } from './access-contracts.rule';

export function normalizeServerOrigin(value: string): string {
  try {
    if (
      value.length > 2048 ||
      /[\s\\]/.test(value) ||
      [...value].some(
        (character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
      ) ||
      /:\/?$/.test(value)
    )
      throw new Error();
    const url = new URL(value);
    if (
      (url.protocol !== 'http:' && url.protocol !== 'https:') ||
      url.username ||
      url.password ||
      url.pathname !== '/' ||
      url.search ||
      url.hash
    )
      throw new Error();
    return parseAccessContract('ServerOrigin', url.origin);
  } catch {
    throw new Error('The server origin is invalid.');
  }
}
