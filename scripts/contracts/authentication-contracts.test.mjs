import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { contractsRoot, readYamlFile } from "./tools.mjs";

function schemas() {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  ajv.addKeyword("discriminator");
  for (const name of readdirSync(resolve(contractsRoot, "schemas"))) {
    if (name.endsWith(".json"))
      ajv.addSchema(
        JSON.parse(
          readFileSync(resolve(contractsRoot, "schemas", name), "utf8"),
        ),
      );
  }
  const document = readYamlFile(resolve(contractsRoot, "openapi/openapi.yaml"));
  const definitions = JSON.parse(
    JSON.stringify(document.components.schemas).replaceAll(
      "#/components/schemas/",
      "#/$defs/",
    ),
  );
  ajv.addSchema({ $id: "authentication-openapi", $defs: definitions });
  return { ajv, document };
}

// These deterministic byte strings check wire shape only, not a cryptographic ceremony.
const randomValue = Buffer.alloc(32, 7).toString("base64url");
const cases = {
  CsrfBootstrap: { token: "synthetic-csrf-value", headerName: "X-CSRF-TOKEN" },
  PasswordLoginRequest: {
    login: "synthetic-gardener",
    password: "Synthetic fixture password",
  },
  AccountSetupRequest: {
    ticket: randomValue,
    login: "synthetic-gardener",
    password: "Synthetic fixture password",
  },
  WebAuthnRegistrationOptions: {
    challenge: randomValue,
    rp: { id: "localhost", name: "Hortinis" },
    user: {
      id: randomValue,
      name: "synthetic-gardener",
      displayName: "Hortinis account",
    },
    pubKeyCredParams: [{ type: "public-key", alg: -7 }],
    timeout: 300000,
    excludeCredentials: [],
    authenticatorSelection: {
      residentKey: "required",
      userVerification: "required",
    },
    attestation: "none",
  },
  WebAuthnAuthenticationOptions: {
    challenge: randomValue,
    rpId: "localhost",
    timeout: 300000,
    userVerification: "required",
  },
  RegisterPasskeyRequest: {
    id: "AA",
    rawId: "AA",
    type: "public-key",
    response: { clientDataJSON: "e30", attestationObject: "AA" },
  },
  AuthenticatePasskeyRequest: {
    id: "AA",
    rawId: "AA",
    type: "public-key",
    response: {
      clientDataJSON: "e30",
      authenticatorData: "AA",
      signature: "AA",
      userHandle: randomValue,
    },
  },
  AuthenticationStatus: {
    state: "authenticated",
    accountId: "01890f3e-7c5a-7b12-8abc-000000000001",
    logoutTarget: randomValue,
    absoluteExpiresAt: "2026-10-11T12:00:00Z",
  },
  RecoveryCodeStatus: {
    remaining: 10,
    expiresAt: "2027-10-11T12:00:00Z",
    expiresSoon: false,
  },
  RecoveryCodeSet: {
    codes: Array.from({ length: 10 }, (_, index) =>
      Buffer.alloc(32, index).toString("base64url"),
    ),
    expiresAt: "2027-10-11T12:00:00Z",
  },
  LogoutRequest: { target: randomValue },
};

test("authentication wire models are closed in standalone and reachable OpenAPI schemas", () => {
  const { ajv } = schemas();
  for (const [name, value] of Object.entries(cases)) {
    for (const id of [
      name + ".json",
      "authentication-openapi#/$defs/Models." + name,
    ]) {
      const validate = ajv.getSchema(id);
      assert.ok(validate, name);
      assert.equal(validate(value), true, name + ": valid structural fixture");
      assert.equal(
        validate({ ...value, sessionSecret: "prohibited-fixture" }),
        false,
        name,
      );
      const incomplete = { ...value };
      delete incomplete[Object.keys(value)[0]];
      assert.equal(validate(incomplete), false, name + ": required field");
    }
  }
});

test("factor options cannot weaken verification or leak credential selection", () => {
  const { ajv } = schemas();
  const registration = ajv.getSchema("WebAuthnRegistrationOptions.json");
  const authentication = ajv.getSchema("WebAuthnAuthenticationOptions.json");
  assert.equal(
    registration({
      ...cases.WebAuthnRegistrationOptions,
      authenticatorSelection: {
        residentKey: "required",
        userVerification: "preferred",
      },
    }),
    false,
  );
  assert.equal(
    authentication({
      ...cases.WebAuthnAuthenticationOptions,
      userVerification: "preferred",
    }),
    false,
  );
  assert.equal(
    authentication({
      ...cases.WebAuthnAuthenticationOptions,
      allowCredentials: [{ type: "public-key", id: "AA" }],
    }),
    false,
  );
  assert.equal(
    registration({
      ...cases.WebAuthnRegistrationOptions,
      pubKeyCredParams: [{ type: "public-key", alg: -999 }],
    }),
    false,
  );
  assert.equal(
    ajv.getSchema("RegisterPasskeyRequest.json")({
      ...cases.RegisterPasskeyRequest,
      response: {
        ...cases.RegisterPasskeyRequest.response,
        privateKey: "prohibited-fixture",
      },
    }),
    false,
  );
});

test("secret envelopes are canonical and bounded and anonymous status has no identity", () => {
  const { ajv } = schemas();
  const encoded = ajv.getSchema("EncodedRandomValue.json");
  assert.equal(encoded(randomValue), true);
  for (const value of [
    randomValue + "=",
    "B".repeat(43),
    "A".repeat(42),
    "A".repeat(44),
  ])
    assert.equal(encoded(value), false);
  const credential = ajv.getSchema("WebAuthnCredentialId.json");
  for (const value of ["", "A", "AB", "AAA=", "A".repeat(2052)])
    assert.equal(credential(value), false);
  assert.equal(credential("AA"), true);
  assert.equal(ajv.getSchema("NewPassword.json")("🌱".repeat(15)), true);
  assert.equal(ajv.getSchema("NewPassword.json")("🌱".repeat(14)), false);
  assert.equal(ajv.getSchema("NewPassword.json")("🌱".repeat(129)), false);
  const status = ajv.getSchema("AuthenticationStatus.json");
  assert.equal(status({ state: "anonymous" }), true);
  assert.equal(
    status({
      state: "anonymous",
      accountId: cases.AuthenticationStatus.accountId,
    }),
    false,
  );
  assert.equal(status({ state: "anonymous", setupAvailable: true }), false);
  assert.equal(
    ajv.getSchema("RecoveryCodeSet.json")({
      ...cases.RecoveryCodeSet,
      codes: Array(10).fill(randomValue),
    }),
    false,
  );
});

test("cookie and CSRF requirements apply to every unsafe auth operation and remain mode-specific for sync", () => {
  const { document } = schemas();
  assert.equal(
    document.components.securitySchemes.SessionCookieAuth.in,
    "cookie",
  );
  assert.equal(
    document.components.securitySchemes.SessionCookieAuth.name,
    "__Host-hortinis-session",
  );
  assert.equal(document.components.securitySchemes.CsrfHeaderAuth.in, "header");
  assert.equal(
    document.components.securitySchemes.CsrfHeaderAuth.name,
    "X-CSRF-TOKEN",
  );
  const joint = { SessionCookieAuth: [], CsrfHeaderAuth: [] };
  let mutations = 0;
  for (const [path, item] of Object.entries(document.paths)) {
    if (!path.startsWith("/api/v1/auth/")) continue;
    for (const [method, operation] of Object.entries(item)) {
      if (method === "post") {
        mutations++;
        assert.deepEqual(operation.security, [joint], path);
      }
      for (const response of Object.values(operation.responses)) {
        assert.deepEqual(response.headers["Cache-Control"].schema.enum, [
          "no-store",
        ]);
        assert.equal(response.headers["Cache-Control"].required, true);
      }
      assert.equal(
        operation.responses["429"].headers["Retry-After"].schema.enum[0],
        "60",
      );
    }
  }
  assert.equal(mutations, 22);
  assert.deepEqual(document.paths["/api/v1/auth/csrf"].get.security, [{}]);
  for (const path of [
    "/api/v1/sync/operations",
    "/api/v1/sync/bindings/confirm",
  ])
    assert.deepEqual(document.paths[path].post.security, [joint, {}], path);
});


test("passkey labels exclude NUL while preserving valid Unicode scalar text", () => {
  const { ajv } = schemas();
  for (const id of ["RegisterPasskeyRequest.json", "authentication-openapi#/$defs/Models.RegisterPasskeyRequest"]) {
    const validate = ajv.getSchema(id);
    assert.equal(validate({ ...cases.RegisterPasskeyRequest, label: "Garden 🌱 key" }), true);
    assert.equal(validate({ ...cases.RegisterPasskeyRequest, label: "Invalid\u0000label" }), false);
  }
});

test("recovery availability is minimized and closed in each state", () => {
  const { ajv } = schemas();
  const validate = ajv.getSchema("RecoveryCodeStatus.json");
  assert.equal(validate({ remaining: 0, expiresSoon: false }), true);
  for (const value of [
    { remaining: 0, expiresSoon: true },
    { remaining: 1, expiresSoon: false },
    { ...cases.RecoveryCodeStatus, remaining: 11 },
    { ...cases.RecoveryCodeStatus, remaining: -1 },
    { ...cases.RecoveryCodeStatus, remaining: 1.5 },
    { ...cases.RecoveryCodeStatus, secretVerifier: "private" },
    { ...cases.RecoveryCodeStatus, codes: [] },
  ]) assert.equal(validate(value), false);
});
