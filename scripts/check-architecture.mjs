import { ESLint } from 'eslint';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import ts from 'typescript';
import { fileURLToPath, pathToFileURL } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const webDirectory = path.resolve(scriptDirectory, '..');
function collectTypeScriptFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) return collectTypeScriptFiles(entryPath);
    if (entry.isFile() && entry.name.endsWith('.ts') && !entry.name.endsWith('.d.ts')) {
      return [entryPath];
    }
    return [];
  });
}

function collectModuleSpecifiers(sourceFile) {
  const specifiers = [];
  const visit = (node) => {
    if (
      (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
      node.moduleSpecifier &&
      ts.isStringLiteral(node.moduleSpecifier)
    ) {
      specifiers.push(node.moduleSpecifier.text);
    } else if (
      ts.isCallExpression(node) &&
      node.expression.kind === ts.SyntaxKind.ImportKeyword &&
      node.arguments.length === 1 &&
      ts.isStringLiteral(node.arguments[0])
    ) {
      specifiers.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return specifiers;
}

function findImportCycles(files, sourceRoot) {
  const fileSet = new Set(files.map((file) => path.resolve(file)));
  const options = ts.readConfigFile(path.join(webDirectory, 'tsconfig.app.json'), ts.sys.readFile);
  const compilerOptions = ts.parseJsonConfigFileContent(
    options.config,
    ts.sys,
    webDirectory,
  ).options;
  const graph = new Map();

  for (const file of fileSet) {
    const source = fs.readFileSync(file, 'utf8');
    const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
    const dependencies = collectModuleSpecifiers(sourceFile)
      .map(
        (specifier) =>
          ts.resolveModuleName(specifier, file, compilerOptions, ts.sys).resolvedModule
            ?.resolvedFileName,
      )
      .filter((resolved) => resolved && fileSet.has(path.resolve(resolved)))
      .map((resolved) => path.resolve(resolved));
    graph.set(file, dependencies);
  }

  const visited = new Set();
  const active = new Set();
  const stack = [];
  const cycles = [];
  const visit = (file) => {
    if (active.has(file)) {
      const start = stack.indexOf(file);
      cycles.push([...stack.slice(start), file]);
      return;
    }
    if (visited.has(file)) return;

    active.add(file);
    stack.push(file);
    for (const dependency of graph.get(file) ?? []) visit(dependency);
    stack.pop();
    active.delete(file);
    visited.add(file);
  };

  for (const file of graph.keys()) visit(file);
  return cycles.map((cycle) => cycle.map((file) => path.relative(sourceRoot, file)));
}

export async function checkArchitecture(sourcePath = 'src/app') {
  const sourceDirectory = path.resolve(webDirectory, sourcePath);
  if (!fs.existsSync(sourceDirectory) || !fs.statSync(sourceDirectory).isDirectory()) {
    throw new Error(`Architecture check source directory does not exist: ${sourceDirectory}`);
  }

  const eslint = new ESLint({ cwd: webDirectory });
  const lintResults = await eslint.lintFiles([
    path.relative(webDirectory, sourceDirectory) + '/**/*.ts',
  ]);
  const cycles = findImportCycles(collectTypeScriptFiles(sourceDirectory), sourceDirectory);
  return { eslint, lintResults, cycles, sourceDirectory };
}

async function main() {
  let result;
  try {
    result = await checkArchitecture(process.argv[2] ?? 'src/app');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
    return;
  }

  const formatter = await result.eslint.loadFormatter('stylish');
  const lintOutput = formatter.format(result.lintResults);

  if (lintOutput) process.stdout.write(lintOutput);
  for (const cycle of result.cycles) {
    console.error(`Import cycle: ${cycle.join(' -> ')}`);
  }

  if (result.lintResults.some((entry) => entry.errorCount > 0) || result.cycles.length > 0) {
    process.exitCode = 1;
    return;
  }

  console.log(
    `Architecture checks passed for ${path.relative(webDirectory, result.sourceDirectory)}.`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await main();
}
