import { Injectable } from '@angular/core';

type JsonObject = Record<string, unknown>;

/** Converts only the closed S9 WebAuthn projection to and from browser credential objects. */
@Injectable({ providedIn: 'root' })
export class BrowserWebAuthnAdapter {
  async assert(options: JsonObject): Promise<JsonObject> {
    const publicKey = options as {
      challenge?: unknown;
      rpId?: unknown;
      timeout?: unknown;
    };
    if (typeof publicKey.challenge !== 'string' || typeof publicKey.rpId !== 'string') {
      throw new Error('The sign-in options are invalid.');
    }
    const credential = await navigator.credentials.get({
      publicKey: {
        challenge: decode(publicKey.challenge),
        rpId: publicKey.rpId,
        timeout: numberValue(publicKey.timeout),
        userVerification: 'required',
      },
    });
    if (!(credential instanceof PublicKeyCredential)) {
      throw new Error('Passkey sign-in was cancelled.');
    }
    const response = credential.response;
    if (!(response instanceof AuthenticatorAssertionResponse) || !response.userHandle) {
      throw new Error('This passkey did not return a verified account handle.');
    }
    const id = encode(new Uint8Array(credential.rawId));
    return {
      id,
      rawId: id,
      type: 'public-key',
      response: {
        clientDataJSON: encode(new Uint8Array(response.clientDataJSON)),
        authenticatorData: encode(new Uint8Array(response.authenticatorData)),
        signature: encode(new Uint8Array(response.signature)),
        userHandle: encode(new Uint8Array(response.userHandle)),
      },
    };
  }

  async register(options: JsonObject): Promise<JsonObject> {
    const publicKey = options as {
      challenge?: unknown;
      rp?: { id?: unknown; name?: unknown };
      user?: { id?: unknown; name?: unknown; displayName?: unknown };
      pubKeyCredParams?: PublicKeyCredentialParameters[];
      excludeCredentials?: readonly { readonly id?: unknown }[];
      timeout?: unknown;
      authenticatorSelection?: AuthenticatorSelectionCriteria;
      attestation?: AttestationConveyancePreference;
    };
    if (
      typeof publicKey.challenge !== 'string' ||
      typeof publicKey.rp?.id !== 'string' ||
      typeof publicKey.user?.id !== 'string' ||
      typeof publicKey.user.name !== 'string' ||
      !Array.isArray(publicKey.pubKeyCredParams)
    ) {
      throw new Error('The passkey registration options are invalid.');
    }
    const credential = await navigator.credentials.create({
      publicKey: {
        challenge: decode(publicKey.challenge),
        rp: { id: publicKey.rp.id, name: String(publicKey.rp.name ?? 'Hortinis') },
        user: {
          id: decode(publicKey.user.id),
          name: publicKey.user.name,
          displayName: String(publicKey.user.displayName ?? 'Hortinis account'),
        },
        pubKeyCredParams: publicKey.pubKeyCredParams,
        excludeCredentials: (publicKey.excludeCredentials ?? []).map((item) => ({
          type: 'public-key' as const,
          id: decode(String(item.id)),
        })),
        timeout: numberValue(publicKey.timeout),
        authenticatorSelection: publicKey.authenticatorSelection,
        attestation: publicKey.attestation,
      },
    });
    if (!(credential instanceof PublicKeyCredential)) {
      throw new Error('Passkey setup was cancelled.');
    }
    const response = credential.response;
    if (!(response instanceof AuthenticatorAttestationResponse)) {
      throw new Error('The browser returned an invalid passkey registration.');
    }
    const id = encode(new Uint8Array(credential.rawId));
    return {
      id,
      rawId: id,
      type: 'public-key',
      response: {
        clientDataJSON: encode(new Uint8Array(response.clientDataJSON)),
        attestationObject: encode(new Uint8Array(response.attestationObject)),
      },
    };
  }
}

function decode(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1) {
    throw new Error('The account service returned invalid binary data.');
  }
  const binary = atob(
    value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - (value.length % 4)) % 4),
  );
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  if (encode(bytes) !== value) throw new Error('The account service returned invalid binary data.');
  return bytes as Uint8Array<ArrayBuffer>;
}

function encode(value: Uint8Array): string {
  let binary = '';
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
