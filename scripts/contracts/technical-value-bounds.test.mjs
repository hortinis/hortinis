import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { contractsRoot, readYamlFile } from "./tools.mjs";

const fixtureDirectory = resolve(contractsRoot, "sync/fixtures");
const fixture = (name) =>
  JSON.parse(readFileSync(resolve(fixtureDirectory, name), "utf8"));

function validators() {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  ajv.addKeyword("discriminator");
  for (const name of readdirSync(resolve(contractsRoot, "schemas"))) {
    if (name.endsWith(".json")) {
      ajv.addSchema(
        JSON.parse(
          readFileSync(resolve(contractsRoot, "schemas", name), "utf8"),
        ),
      );
    }
  }
  const document = readYamlFile(resolve(contractsRoot, "openapi/openapi.yaml"));
  const definitions = JSON.parse(
    JSON.stringify(document.components.schemas).replaceAll(
      "#/components/schemas/",
      "#/$defs/",
    ),
  );
  ajv.addSchema({ $id: "technical-bounds-openapi", $defs: definitions });
  return ajv;
}

test("both generated contracts consume the shared technical value fixtures", () => {
  const ajv = validators();
  const validateFunctions = [
    ajv.getSchema("TechnicalRecordOperation.json"),
    ajv.getSchema(
      "technical-bounds-openapi#/$defs/Models.TechnicalRecordOperation",
    ),
  ];
  const declarations = fixture("capabilities.json").fixtures.filter(
    ({ capability }) => capability === "technical-value-bounds",
  );
  assert.equal(declarations.length, 5);
  for (const declaration of declarations) {
    const example = fixture(declaration.file);
    for (const validate of validateFunctions) {
      assert.ok(validate);
      assert.equal(
        validate(example.request),
        example.expected.valid,
        example.id,
      );
      assert.equal(
        validate({
          ...example.request,
          kind: "replace",
          expectedRevision: "1",
        }),
        example.expected.valid,
        `${example.id}: replace`,
      );
    }
  }
});

test("value bounds count supplementary code points and also constrain returned records", () => {
  const ajv = validators();
  for (const name of [
    "TechnicalRecordValue.json",
    "technical-bounds-openapi#/$defs/Models.TechnicalRecordValue",
  ]) {
    const validate = ajv.getSchema(name);
    assert.ok(validate);
    for (const value of [
      "",
      " \n\t",
      "e\u0301",
      "x".repeat(4096),
      "🌱".repeat(4096),
    ]) {
      assert.equal(validate(value), true);
    }
    for (const value of [
      "x".repeat(4097),
      "🌱".repeat(4097),
      "\u0000",
      "\uD800",
      "\uDC00",
      "🌱\uD800",
    ]) {
      assert.equal(validate(value), false);
    }
  }
  for (const name of [
    "TechnicalRecord.json",
    "technical-bounds-openapi#/$defs/Models.TechnicalRecord",
  ]) {
    const validate = ajv.getSchema(name);
    const request = fixture("invalid-value-nul.json").request;
    const record = {
      recordId: request.recordId,
      revision: "1",
      value: "🌱".repeat(4096),
    };
    assert.ok(validate);
    assert.equal(validate(record), true);
    assert.equal(validate({ ...record, value: "\u0000" }), false);
    assert.equal(validate({ ...record, value: "x".repeat(4097) }), false);
  }
});
