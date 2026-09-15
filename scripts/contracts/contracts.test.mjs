import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { compile, NodeHost } from "@typespec/compiler";
import {
  assertLocalReferences,
  assertTypeSpecSuccess,
  compileTypeSpecProject,
  contractsRoot,
  readYamlFile,
  repositoryRoot,
} from "./tools.mjs";

function compileSchema(schema) {
  const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
  addFormats(ajv);
  return ajv.compile(schema);
}

function readJsonFixture(name) {
  return JSON.parse(readFileSync(resolve(repositoryRoot, "tooling/contracts/fixtures", name), "utf8"));
}

function loadProductionSchemaValidators() {
  const schemaDirectory = resolve(contractsRoot, "schemas");
  const schemas = readdirSync(schemaDirectory)
    .filter((name) => name.endsWith(".json"))
    .map((name) => JSON.parse(readFileSync(resolve(schemaDirectory, name), "utf8")));
  const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
  addFormats(ajv);
  for (const schema of schemas) {
    ajv.addSchema(schema);
  }
  return ajv;
}

test("TypeSpec emits matching OpenAPI 3.1 and Draft 2020-12 JSON Schema wire shapes", async (context) => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "hortinis-contract-probe-"));
  context.after(() => rmSync(temporaryDirectory, { recursive: true, force: true }));
  const openApiDirectory = join(temporaryDirectory, "openapi");
  const schemaDirectory = join(temporaryDirectory, "schemas");

  const program = await compileTypeSpecProject(
    resolve(repositoryRoot, "tooling/contracts/fixtures/compatibility"),
    {
      "@typespec/openapi3": { "emitter-output-dir": openApiDirectory },
      "@typespec/json-schema": { "emitter-output-dir": schemaDirectory },
    },
  );
  assertTypeSpecSuccess(program, "The compatibility fixture");

  const openApiPath = join(openApiDirectory, "probe.openapi.yaml");
  const schemaPath = join(schemaDirectory, "CompatibilityProbe.json");
  const openApi = readYamlFile(openApiPath);
  const generatedSchema = JSON.parse(readFileSync(schemaPath, "utf8"));

  assert.equal(openApi.openapi, "3.1.0");
  assert.ok(openApi.paths["/contract-probe"]?.get);
  assertLocalReferences(openApi, openApiPath, temporaryDirectory);

  const openApiSchema = openApi.components.schemas["Models.CompatibilityProbe"];
  assert.ok(openApiSchema);
  const validators = [compileSchema(openApiSchema), compileSchema(generatedSchema)];
  const validPayload = readJsonFixture("valid-compatibility-probe.json");
  const invalidPayloads = [
    readJsonFixture("invalid-compatibility-probe-missing-required.json"),
    readJsonFixture("invalid-compatibility-probe-extra-property.json"),
    { ...validPayload, id: "" },
    { ...validPayload, kind: "unknown" },
  ];

  for (const validate of validators) {
    assert.equal(validate(validPayload), true);
    for (const invalidPayload of invalidPayloads) {
      assert.equal(validate(invalidPayload), false, JSON.stringify(invalidPayload));
    }
  }
});

test("the committed OpenAPI contract exposes only the versioned technical synchronization operations", () => {
  const openApiPath = resolve(contractsRoot, "openapi/openapi.yaml");
  const document = readYamlFile(openApiPath);
  assert.equal(document.openapi, "3.1.0");
  assert.equal(document.info.version, "1.0.0");
  assert.deepEqual(Object.keys(document.paths).sort(), [
    "/api/v1/sync/changes",
    "/api/v1/sync/operations",
  ]);
  assert.equal(
    document.paths["/api/v1/sync/operations"].post.requestBody.content["application/json"].schema.$ref,
    "#/components/schemas/Models.TechnicalRecordOperation",
  );
  assert.deepEqual(
    Object.keys(document.paths["/api/v1/sync/operations"].post.responses).sort(),
    ["200", "400", "404", "409"],
  );
  assert.equal(
    document.paths["/api/v1/sync/changes"].get.parameters[0].schema.$ref,
    "#/components/schemas/Models.SyncCursor",
  );
  assert.deepEqual(Object.keys(document.paths["/api/v1/sync/changes"].get.responses).sort(), [
    "200",
    "400",
  ]);
  assertLocalReferences(document, openApiPath);
});

test("production schemas enforce technical operations, identifiers, revisions, and closed objects", () => {
  const ajv = loadProductionSchemaValidators();
  const validateOperation = ajv.getSchema("TechnicalRecordOperation.json");
  const validateResult = ajv.getSchema("OperationResult.json");
  assert.ok(validateOperation);
  assert.ok(validateResult);

  const operationId = "01890f3e-7c5a-7b12-8abc-0123456789ab";
  const recordId = "01890f3e-7c5a-7b13-8abc-0123456789ab";
  const create = {
    protocolVersion: 1,
    operationId,
    recordId,
    value: "first value",
    kind: "create",
  };
  const replace = {
    ...create,
    operationId: "01890f3e-7c5a-7b14-8abc-0123456789ab",
    value: "replacement value",
    kind: "replace",
    expectedRevision: "1",
  };

  assert.equal(validateOperation(create), true, JSON.stringify(validateOperation.errors));
  assert.equal(validateOperation(replace), true, JSON.stringify(validateOperation.errors));
  assert.equal(validateOperation({ ...create, expectedRevision: "1" }), false);
  const replaceWithoutRevision = { ...replace };
  delete replaceWithoutRevision.expectedRevision;
  assert.equal(validateOperation(replaceWithoutRevision), false);
  assert.equal(validateOperation({ ...create, unexpected: true }), false);
  assert.equal(validateOperation({ ...create, operationId: "01890f3e-7c5a-4b12-8abc-0123456789ab" }), false);
  assert.equal(validateOperation({ ...create, recordId: recordId.toUpperCase() }), false);

  const result = {
    protocolVersion: 1,
    outcome: "accepted",
    operationId,
    record: { recordId, revision: "1", value: "first value" },
    sequence: "9007199254740993",
  };
  assert.equal(validateResult(result), true, JSON.stringify(validateResult.errors));
  assert.equal(validateResult({ ...result, sequence: "0" }), false);
  assert.equal(validateResult({ ...result, record: { ...result.record, revision: 1 } }), false);
});

test("production schemas enforce opaque cursors and explicit protocol errors", () => {
  const ajv = loadProductionSchemaValidators();
  const validatePage = ajv.getSchema("ChangePage.json");
  const validateConflict = ajv.getSchema("ConflictError.json");
  assert.ok(validatePage);
  assert.ok(validateConflict);

  const operationId = "01890f3e-7c5a-7b12-8abc-0123456789ab";
  const record = {
    recordId: "01890f3e-7c5a-7b13-8abc-0123456789ab",
    revision: "2",
    value: "accepted value",
  };
  const page = {
    protocolVersion: 1,
    changes: [{ operationId, record, sequence: "2" }],
    nextCursor: "opaque-cursor-value",
    hasMore: false,
  };
  assert.equal(validatePage(page), true, JSON.stringify(validatePage.errors));
  assert.equal(validatePage({ ...page, nextCursor: "" }), false);

  const revisionConflict = {
    protocolVersion: 1,
    code: "REVISION_CONFLICT",
    message: "The expected revision does not match the current revision.",
    operationId,
    expectedRevision: "1",
    currentRecord: record,
  };
  const explicitErrors = [
    {
      schema: "InvalidRequestError.json",
      value: { protocolVersion: 1, code: "INVALID_REQUEST", message: "The request is invalid." },
    },
    {
      schema: "RecordNotFoundError.json",
      value: {
        protocolVersion: 1,
        code: "RECORD_NOT_FOUND",
        message: "The record does not exist.",
        operationId,
        recordId: record.recordId,
      },
    },
    {
      schema: "OperationIdReusedError.json",
      value: {
        protocolVersion: 1,
        code: "OPERATION_ID_REUSED",
        message: "The operation identifier was reused.",
        operationId,
      },
    },
    {
      schema: "RecordAlreadyExistsError.json",
      value: {
        protocolVersion: 1,
        code: "RECORD_ALREADY_EXISTS",
        message: "The record already exists.",
        operationId,
        currentRecord: record,
      },
    },
    { schema: "RevisionConflictError.json", value: revisionConflict },
  ];
  for (const { schema, value } of explicitErrors) {
    const validate = ajv.getSchema(schema);
    assert.ok(validate);
    assert.equal(validate(value), true, JSON.stringify(validate.errors));
    assert.equal(validate({ ...value, unexpected: true }), false);
  }
  assert.equal(validateConflict(revisionConflict), true, JSON.stringify(validateConflict.errors));
  assert.equal(validateConflict({ ...revisionConflict, code: "UNKNOWN_ERROR" }), false);
  const conflictWithoutCurrentRecord = { ...revisionConflict };
  delete conflictWithoutCurrentRecord.currentRecord;
  assert.equal(validateConflict(conflictWithoutCurrentRecord), false);
});

test("invalid TypeSpec is rejected", async (context) => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "hortinis-invalid-typespec-"));
  context.after(() => rmSync(temporaryDirectory, { recursive: true, force: true }));
  const source = readFileSync(resolve(repositoryRoot, "tooling/contracts/fixtures/invalid-typespec.txt"), "utf8");
  const entrypoint = join(temporaryDirectory, "main.tsp");
  writeFileSync(entrypoint, source);

  const program = await compile(NodeHost, entrypoint, { noEmit: true, warningAsError: true });
  assert.ok(program.diagnostics.length > 0);
});

test("an invalid JSON Schema definition is rejected", () => {
  const invalidSchema = readYamlFile(resolve(repositoryRoot, "tooling/contracts/fixtures/invalid-schema.json"));
  assert.throws(() => compileSchema(invalidSchema));
});

test("unresolved and external references are rejected before resolution", () => {
  const localDocument = resolve(contractsRoot, "openapi/openapi.yaml");
  assert.throws(
    () => assertLocalReferences({ $ref: "../schemas/missing.json#/$defs/Record" }, localDocument),
    /unresolved local reference/,
  );
  assert.throws(
    () => assertLocalReferences({ $ref: "https://example.invalid/schema.json" }, localDocument),
    /external reference.*not allowed/,
  );
});
