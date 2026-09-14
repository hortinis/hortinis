import { existsSync, readFileSync } from "node:fs";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { compile, NodeHost } from "@typespec/compiler";
import { parseDocument } from "yaml";

export const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
export const contractsRoot = resolve(repositoryRoot, "contracts");

export async function compileTypeSpecProject(projectDirectory, optionOverrides = {}) {
  const absoluteProjectDirectory = resolve(projectDirectory);
  const projectConfigPath = resolve(absoluteProjectDirectory, "tspconfig.yaml");
  const projectConfig = readYamlFile(projectConfigPath);
  const emitterOptions = Object.fromEntries(
    Object.entries(projectConfig.options ?? {}).map(([emitter, options]) => [
      emitter,
      Object.fromEntries(
        Object.entries({ ...options, ...optionOverrides[emitter] }).map(([name, value]) => [
          name,
          typeof value === "string"
            ? value.replaceAll("{project-root}", absoluteProjectDirectory)
            : value,
        ]),
      ),
    ]),
  );
  const entrypoint = resolve(absoluteProjectDirectory, projectConfig.entrypoint ?? "main.tsp");

  return compile(NodeHost, entrypoint, {
    emit: projectConfig.emit ?? [],
    options: emitterOptions,
    warningAsError: true,
  });
}

export function assertTypeSpecSuccess(program, label) {
  if (program.diagnostics.length > 0) {
    const diagnosticText = program.diagnostics
      .map(({ severity, code, message }) => `${severity} ${code}: ${String(message)}`)
      .join("\n");
    throw new Error(`${label} produced TypeSpec diagnostics:\n${diagnosticText}`);
  }
}

export function readYamlFile(filePath) {
  const document = parseDocument(readFileSync(filePath, "utf8"), {
    prettyErrors: true,
    uniqueKeys: true,
  });
  if (document.errors.length > 0) {
    throw document.errors[0];
  }
  return document.toJS();
}

function findReferences(value, path = "$") {
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => findReferences(item, `${path}[${index}]`));
  }

  if (value === null || typeof value !== "object") {
    return [];
  }

  return Object.entries(value).flatMap(([key, child]) => {
    if (key === "$ref" && typeof child === "string") {
      return [{ reference: child, path: `${path}.$ref` }];
    }
    return findReferences(child, `${path}.${key}`);
  });
}

export function assertLocalReferences(document, documentPath, allowedRoot = contractsRoot) {
  const absoluteDocumentPath = resolve(documentPath);
  const absoluteAllowedRoot = resolve(allowedRoot);

  for (const { reference, path } of findReferences(document)) {
    if (/^[a-z][a-z\d+.-]*:/i.test(reference) || reference.startsWith("//")) {
      throw new Error(`${documentPath}: external reference at ${path} is not allowed: ${reference}`);
    }
    if (reference.includes("\\")) {
      throw new Error(`${documentPath}: backslashes are not allowed in reference at ${path}: ${reference}`);
    }

    const referencePath = reference.split("#", 1)[0];
    if (referencePath.length === 0) {
      continue;
    }

    let decodedPath;
    try {
      decodedPath = decodeURIComponent(referencePath);
    } catch {
      throw new Error(`${documentPath}: malformed escaped reference at ${path}: ${reference}`);
    }

    const targetPath = resolve(dirname(absoluteDocumentPath), decodedPath);
    const relativeTarget = relative(absoluteAllowedRoot, targetPath);
    if (relativeTarget === ".." || relativeTarget.startsWith(`..${sep}`) || relativeTarget.startsWith(sep)) {
      throw new Error(`${documentPath}: reference at ${path} escapes the contracts directory: ${reference}`);
    }
    if (!existsSync(targetPath)) {
      throw new Error(`${documentPath}: unresolved local reference at ${path}: ${reference}`);
    }
  }
}
