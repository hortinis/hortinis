import { normalizeServerOrigin } from './server-origin.rule';

describe('binding origin normalization', () => {
  it('normalizes case, default ports, root slash and international hostnames', () => {
    expect(normalizeServerOrigin('https://BÜCHER.example:443/')).toBe(
      'https://xn--bcher-kva.example',
    );
    expect(normalizeServerOrigin('http://LOCALHOST:4200/')).toBe('http://localhost:4200');
  });

  it.each([
    'https://user:private@server.example',
    'https://server.example/path',
    'https://server.example?query=private',
    'https://server.example#private',
    ' https://server.example',
    'https://server.example:',
    'https://server.example:65536',
    'file:///tmp/private',
    'https://server.example\n',
    'https://ser\nver.example',
    'https://ser\tver.example',
    'https://server.example\\',
    'https://%00.example',
  ])('rejects invalid origin without echoing it: %s', (value) => {
    expect(() => normalizeServerOrigin(value)).toThrow('The server origin is invalid.');
  });
});
