import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { assertTypeSpecSuccess, compileTypeSpecProject, repositoryRoot } from "./tools.mjs";

const temporaryDirectory = mkdtempSync(join(tmpdir(), "hortinis-openapi-"));
const generatedOpenApiDirectory = join(temporaryDirectory, "openapi");
const generatedSchemaDirectory = join(temporaryDirectory, "schemas");

function listGeneratedFiles(directory, extensions, rootDirectory = directory) {
  return readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const entryPath = join(directory, entry.name);
      return entry.isDirectory()
        ? listGeneratedFiles(entryPath, extensions, rootDirectory)
        : extensions.some((extension) => entry.name.endsWith(extension))
          ? [relative(rootDirectory, entryPath)]
          : [];
    })
    .sort();
}

function compareGeneratedDirectory(label, generatedDirectory, committedDirectory, extensions) {
  const generatedFiles = listGeneratedFiles(generatedDirectory, extensions);
  const committedFiles = listGeneratedFiles(committedDirectory, extensions);

  if (JSON.stringify(generatedFiles) !== JSON.stringify(committedFiles)) {
    throw new Error(
      `${label} file set is out of date. Expected ${JSON.stringify(generatedFiles)}, found ${JSON.stringify(committedFiles)}.`,
    );
  }

  for (const path of generatedFiles) {
    const generated = readFileSync(join(generatedDirectory, path), "utf8");
    const committed = readFileSync(join(committedDirectory, path), "utf8");
    if (generated !== committed) {
      throw new Error(`${label} artifact is out of date: ${path}.`);
    }
  }
}

try {
  const program = await compileTypeSpecProject(`${repositoryRoot}/contracts/typespec`, {
    "@typespec/openapi3": { "emitter-output-dir": generatedOpenApiDirectory },
    "@typespec/json-schema": { "emitter-output-dir": generatedSchemaDirectory },
  });
  assertTypeSpecSuccess(program, "The generated contract");

  compareGeneratedDirectory(
    "OpenAPI",
    generatedOpenApiDirectory,
    resolve(repositoryRoot, "contracts/openapi"),
    [".yaml", ".yml", ".json"],
  );
  compareGeneratedDirectory(
    "JSON Schema",
    generatedSchemaDirectory,
    resolve(repositoryRoot, "contracts/schemas"),
    [".yaml", ".yml", ".json"],
  );
  process.stdout.write("Generated OpenAPI and JSON Schemas match their TypeSpec source.\n");
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.stderr.write(
    "Run `pnpm contracts:generate` and review the source and generated artifact diff.\n",
  );
  process.exitCode = 1;
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
