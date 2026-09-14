import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { assertTypeSpecSuccess, compileTypeSpecProject, repositoryRoot } from "./tools.mjs";

const temporaryDirectory = mkdtempSync(join(tmpdir(), "hortinis-openapi-"));
const generatedPath = join(temporaryDirectory, "openapi.yaml");
const committedPath = resolve(repositoryRoot, "contracts/openapi/openapi.yaml");

try {
  const program = await compileTypeSpecProject(`${repositoryRoot}/contracts/typespec`, {
    "@typespec/openapi3": { "emitter-output-dir": temporaryDirectory },
  });
  assertTypeSpecSuccess(program, "The generated contract");

  const generated = readFileSync(generatedPath, "utf8");
  const committed = readFileSync(committedPath, "utf8");
  if (generated !== committed) {
    process.stderr.write(
      "Generated OpenAPI is out of date. Run `pnpm contracts:generate` and review the resulting diff.\n",
    );
    process.exitCode = 1;
  } else {
    process.stdout.write("Generated OpenAPI matches its TypeSpec source.\n");
  }
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
