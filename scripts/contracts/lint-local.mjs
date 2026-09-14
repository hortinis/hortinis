import { readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { assertLocalReferences, contractsRoot, readYamlFile } from "./tools.mjs";

function listFiles(directory, extensions) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      return listFiles(entryPath, extensions);
    }
    return extensions.some((extension) => entry.name.endsWith(extension)) ? [entryPath] : [];
  });
}

function parseContract(path) {
  const document = readYamlFile(path);
  assertLocalReferences(document, path);
  return document;
}

const openApiDirectory = resolve(contractsRoot, "openapi");
const schemaDirectory = resolve(contractsRoot, "schemas");
const openApiPaths = listFiles(openApiDirectory, [".yaml", ".yml", ".json"]);
const schemaPaths = listFiles(schemaDirectory, [".json", ".yaml", ".yml"]);

if (openApiPaths.length === 0) {
  throw new Error("No OpenAPI documents were found under contracts/openapi.");
}

for (const openApiPath of openApiPaths) {
  const document = parseContract(openApiPath);
  if (document.openapi !== "3.1.0") {
    throw new Error(`${openApiPath}: expected OpenAPI 3.1.0.`);
  }
  process.stdout.write(`Valid local OpenAPI structure: ${openApiPath}\n`);
}

if (schemaPaths.length === 0) {
  process.stdout.write("No standalone JSON Schemas are defined yet; D2 introduces the first protocol schemas.\n");
} else {
  const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
  addFormats(ajv);

  for (const schemaPath of schemaPaths) {
    const schema = parseContract(schemaPath);
    ajv.compile(schema);
    process.stdout.write(`Valid JSON Schema: ${schemaPath}\n`);
  }
}
