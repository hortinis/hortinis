import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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

test("the committed endpoint-free OpenAPI contract is valid OpenAPI 3.1", () => {
  const openApiPath = resolve(contractsRoot, "openapi/openapi.yaml");
  const document = readYamlFile(openApiPath);
  assert.equal(document.openapi, "3.1.0");
  assert.deepEqual(document.paths, {});
  assertLocalReferences(document, openApiPath);
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
