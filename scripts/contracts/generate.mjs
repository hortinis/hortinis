import {
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import { assertTypeSpecSuccess, compileTypeSpecProject, repositoryRoot } from "./tools.mjs";

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

function synchronizeGeneratedDirectory(generatedDirectory, committedDirectory, extensions) {
  const generatedFiles = listGeneratedFiles(generatedDirectory, extensions);
  const committedFiles = listGeneratedFiles(committedDirectory, extensions);

  for (const path of committedFiles.filter((path) => !generatedFiles.includes(path))) {
    unlinkSync(join(committedDirectory, path));
  }
  for (const path of generatedFiles) {
    const committedPath = join(committedDirectory, path);
    mkdirSync(dirname(committedPath), { recursive: true });
    writeFileSync(committedPath, readFileSync(join(generatedDirectory, path)));
  }
}

const temporaryDirectory = mkdtempSync(join(tmpdir(), "hortinis-contract-generate-"));
const generatedOpenApiDirectory = join(temporaryDirectory, "openapi");
const generatedSchemaDirectory = join(temporaryDirectory, "schemas");

try {
  const program = await compileTypeSpecProject(`${repositoryRoot}/contracts/typespec`, {
    "@typespec/openapi3": { "emitter-output-dir": generatedOpenApiDirectory },
    "@typespec/json-schema": { "emitter-output-dir": generatedSchemaDirectory },
  });
  assertTypeSpecSuccess(program, "The production contract");

  synchronizeGeneratedDirectory(
    generatedOpenApiDirectory,
    resolve(repositoryRoot, "contracts/openapi"),
    [".yaml", ".yml", ".json"],
  );
  synchronizeGeneratedDirectory(
    generatedSchemaDirectory,
    resolve(repositoryRoot, "contracts/schemas"),
    [".yaml", ".yml", ".json"],
  );
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
