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

// Synthetic structural fixtures; these are not cryptographic or runtime evidence.
const token = Buffer.alloc(32, 7).toString("base64url");
const email = "Synthetic.Gardener+fixture@example.test";
const password = "Synthetic fixture password";
const accountId = "01890f3e-7c5a-7b12-8abc-000000000001";
const cases = {
  CsrfBootstrap: { token: "synthetic-csrf-value", headerName: "X-CSRF-TOKEN" },
  PasswordLoginRequest: { email, password },
  InvitationAcceptanceRequest: { token, newPassword: password },
  PasswordResetRequest: { email },
  PasswordResetCompletionRequest: { token, newPassword: password },
  PasswordChangeRequest: { currentPassword: password, newPassword: password },
  AuthenticationStatus: { state: "authenticated", accountId },
  FullAuthenticationStatus: { state: "authenticated", accountId },
  AnonymousAuthenticationStatus: { state: "anonymous" },
  AuthenticationCompleted: { completed: true },
  PasswordResetAccepted: { accepted: true },
  AuthenticationInvalidRequestError: {
    code: "INVALID_REQUEST",
    message: "The request is invalid.",
  },
  AuthenticationFailedError: {
    code: "AUTHENTICATION_FAILED",
    message: "Authentication failed.",
  },
  AuthenticationPreconditionFailedError: {
    code: "AUTHENTICATION_PRECONDITION_FAILED",
    message: "The authentication precondition no longer holds.",
  },
  AuthenticationThrottledError: {
    code: "AUTHENTICATION_THROTTLED",
    message: "Authentication attempts are temporarily limited.",
  },
};

function validators(ajv, name) {
  return [name + ".json", "authentication-openapi#/$defs/Models." + name].map(
    (id) => {
      const validate = ajv.getSchema(id);
      assert.ok(validate, id);
      return validate;
    },
  );
}

test("replacement authentication models are closed and required in both schema projections", () => {
  const { ajv } = schemas();
  for (const [name, value] of Object.entries(cases)) {
    for (const validate of validators(ajv, name)) {
      assert.equal(validate(value), true, name);
      for (const field of [
        "sessionSecret",
        "emailOverride",
        "scopeId",
        "purpose",
      ])
        assert.equal(
          validate({ ...value, [field]: "prohibited" }),
          false,
          name,
        );
      for (const field of Object.keys(value)) {
        const incomplete = { ...value };
        delete incomplete[field];
        assert.equal(validate(incomplete), false, name + ": " + field);
      }
    }
  }
});

test("ASCII email syntax preserves aliases and rejects ambiguous or unbounded mailboxes", () => {
  const { ajv } = schemas();
  const longest =
    "a".repeat(64) +
    "@" +
    "b".repeat(63) +
    "." +
    "c".repeat(63) +
    "." +
    "d".repeat(61);
  assert.equal(longest.length, 254);
  for (const validate of validators(ajv, "EmailAddress")) {
    for (const value of [email, "a.b@example.test", "A@example.test", longest])
      assert.equal(validate(value), true, value);
    for (const value of [
      " a@example.test",
      "a@example.test ",
      "a@example.test\n",
      "a\n@example.test",
      "a..b@example.test",
      ".a@example.test",
      "a.@example.test",
      "a@-example.test",
      "a@example-.test",
      "a@localhost",
      "a@example.test.",
      "a@[127.0.0.1]",
      '"a"@example.test',
      "a(comment)@example.test",
      "é@example.test",
      "a@é.test",
      "a".repeat(65) + "@example.test",
      "a@" + "b".repeat(64) + ".test",
      longest + "d",
    ])
      assert.equal(validate(value), false, JSON.stringify(value));
  }
});

test("token/password/CSRF bounds and anonymous minimization describe the new target", () => {
  const { ajv } = schemas();
  for (const validate of validators(ajv, "ActionToken")) {
    assert.equal(validate(token), true);
    for (const value of [
      token + "=",
      token + "\n",
      token + "\r\n",
      "B".repeat(43),
      "A".repeat(42),
      "A".repeat(44),
    ])
      assert.equal(validate(value), false);
  }
  for (const validate of validators(ajv, "NewPassword")) {
    assert.equal(validate("🌱".repeat(15)), true);
    assert.equal(validate("🌱".repeat(128)), true);
    for (const value of ["🌱".repeat(14), "🌱".repeat(129)])
      assert.equal(validate(value), false);
  }
  for (const validate of validators(ajv, "CsrfValue")) {
    // Spring's masked token may use ordinary base64, not only base64url.
    assert.equal(validate("synthetic/Masked+value=="), true);
    for (const value of ["", "a".repeat(257), "with whitespace", "bad\nvalue"])
      assert.equal(validate(value), false);
  }
  for (const validate of validators(ajv, "AuthenticationStatus")) {
    assert.equal(validate({ state: "anonymous" }), true);
    for (const value of [
      { state: "anonymous", accountId },
      { state: "pending" },
      { state: "password" },
      { state: "setup" },
      { state: "recovery" },
      { state: "anonymous", setupAvailable: true },
      { ...cases.AuthenticationStatus, logoutTarget: token },
      {
        ...cases.AuthenticationStatus,
        absoluteExpiresAt: "2026-10-17T12:00:00Z",
      },
      { ...cases.AuthenticationStatus, email },
    ])
      assert.equal(validate(value), false);
  }
});

test("login uses the native form encoding and logout has no target body", () => {
  const { ajv, document } = schemas();
  const login = document.paths["/api/v1/auth/login"].post;
  assert.deepEqual(Object.keys(login.requestBody.content), [
    "application/x-www-form-urlencoded",
  ]);
  assert.equal(login.requestBody.required, true);
  const reference =
    login.requestBody.content["application/x-www-form-urlencoded"].schema.$ref;
  assert.equal(reference, "#/components/schemas/Models.PasswordLoginRequest");
  const validate = ajv.getSchema(
    "authentication-openapi#/$defs/Models.PasswordLoginRequest",
  );
  // Form-decoded object shape only; servlet percent/duplicate/byte validation is R2 work.
  assert.equal(validate({ email, password }), true);
  for (const value of [
    { username: email, password },
    { email, password, rememberMe: true },
    { email: [email, email], password },
    { email, password: [password, password] },
  ])
    assert.equal(validate(value), false);
  assert.equal(
    document.paths["/api/v1/auth/logout"].post.requestBody,
    undefined,
  );
  const reset = document.paths["/api/v1/auth/password-reset/request"].post;
  assert.equal(reset.responses["200"], undefined);
  assert.equal(
    reset.responses["202"].content["application/json"].schema.$ref,
    "#/components/schemas/Models.PasswordResetAccepted",
  );
});

test("the complete auth surface has native cookie/CSRF, no-store and fixed error declarations", () => {
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
  const expected = {
    csrf: ["get", ["200", "400", "403", "429", "503"]],
    session: ["get", ["200", "400", "403", "429", "503"]],
    login: ["post", ["200", "400", "401", "403", "429", "503"]],
    "invitations/accept": ["post", ["200", "400", "403", "409", "429", "503"]],
    "password-reset/request": ["post", ["202", "400", "403", "429", "503"]],
    "password-reset/complete": [
      "post",
      ["200", "400", "403", "409", "429", "503"],
    ],
    password: ["post", ["200", "400", "401", "403", "409", "429", "503"]],
    logout: ["post", ["200", "400", "403", "429", "503"]],
  };
  const paths = Object.keys(document.paths).filter((p) =>
    p.startsWith("/api/v1/auth/"),
  );
  assert.deepEqual(
    paths.sort(),
    Object.keys(expected)
      .map((p) => "/api/v1/auth/" + p)
      .sort(),
  );
  for (const [path, [method, statuses]] of Object.entries(expected)) {
    const item = document.paths["/api/v1/auth/" + path];
    assert.deepEqual(Object.keys(item), [method], path);
    const operation = item[method];
    assert.deepEqual(Object.keys(operation.responses).sort(), statuses, path);
    if (method === "post")
      assert.deepEqual(
        operation.security,
        [{ SessionCookieAuth: [], CsrfHeaderAuth: [] }],
        path,
      );
    for (const response of Object.values(operation.responses)) {
      assert.deepEqual(response.headers["Cache-Control"].schema.enum, [
        "no-store",
      ]);
      assert.equal(response.headers["Cache-Control"].required, true);
      assert.deepEqual(Object.keys(response.content), ["application/json"]);
    }
    assert.deepEqual(
      operation.responses["429"].headers["Retry-After"].schema.enum,
      ["60"],
    );
    assert.equal(
      operation.responses["400"].content["application/json"].schema.$ref,
      "#/components/schemas/Models.AuthenticationInvalidRequestError",
    );
  }
  assert.deepEqual(document.paths["/api/v1/auth/csrf"].get.security, [{}]);
  assert.deepEqual(document.paths["/api/v1/auth/session"].get.security, [
    { SessionCookieAuth: [] },
    {},
  ]);
  for (const path of [
    "/api/v1/sync/operations",
    "/api/v1/sync/bindings/confirm",
  ])
    assert.deepEqual(document.paths[path].post.security, [
      { SessionCookieAuth: [], CsrfHeaderAuth: [] },
      {},
    ]);
});

test("retired authentication models are absent from generated artifacts", () => {
  const { document } = schemas();
  const files = readdirSync(resolve(contractsRoot, "schemas"));
  for (const name of [
    "AccountSetupRequest",
    "WebAuthnRegistrationOptions",
    "WebAuthnAuthenticationOptions",
    "RegisterPasskeyRequest",
    "AuthenticatePasskeyRequest",
    "RecoveryCodeSet",
    "RecoveryCodeStatus",
    "LimitedAuthenticationStatus",
    "OperatorRecoveryRequest",
    "ForgottenPasswordRequest",
    "LostPasskeyRequest",
    "SessionList",
    "LogoutRequest",
    "LoginIdentifier",
  ]) {
    assert.equal(files.includes(name + ".json"), false, name);
    assert.equal(
      document.components.schemas["Models." + name],
      undefined,
      name,
    );
  }
});
