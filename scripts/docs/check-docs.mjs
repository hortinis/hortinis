import { existsSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Cross-repository links are reviewed explicitly; ordinary external references are not fetched.
export const crossRepositoryUrls = new Set([
  "https://github.com/hortinis/hortinis-plants/blob/main/docs/development/implementation-plan.md",
]);

function prose(text) {
  return text.replace(
    /^ {0,3}(`{3,}|~{3,})[^\n]*\n[\s\S]*?^ {0,3}\1[^\n]*$/gm,
    "",
  );
}

function anchors(text) {
  const counts = new Map();
  const result = new Set();
  for (const match of prose(text).matchAll(
    /^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$/gm,
  )) {
    const slug = match[1]
      .toLowerCase()
      .replace(/<[^>]*>/g, "")
      .replace(/[^\p{L}\p{N}_\-\s]/gu, "")
      .replace(/\s/g, "-");
    const count = counts.get(slug) ?? 0;
    result.add(count ? `${slug}-${count}` : slug);
    counts.set(slug, count + 1);
  }
  for (const match of text.matchAll(/\b(?:id|name)=["']([^"']+)["']/g))
    result.add(match[1]);
  return result;
}

export function checkDocumentation(root, files) {
  const errors = [];
  const report = (file, message) => errors.push(`${file}: ${message}`);
  for (const file of files.filter((name) => name.endsWith(".md"))) {
    const text = readFileSync(resolve(root, file), "utf8");
    const content = prose(text);
    const definitions = new Map(
      [...content.matchAll(/^ {0,3}\[([^\]]+)\]:\s*<?([^\s>]+)>?/gm)].map(
        (m) => [m[1].toLowerCase(), m[2]],
      ),
    );
    const links = [
      ...content.matchAll(
        /!?\[[^\]\n]*\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+["'][^\n]*?["'])?\s*\)/g,
      ),
    ].map((m) => m[1] ?? m[2]);
    for (const match of content.matchAll(/!?\[([^\]\n]+)\]\[([^\]\n]*)\]/g)) {
      const label = (match[2] || match[1]).toLowerCase();
      if (!definitions.has(label))
        report(file, `undefined link reference: ${label}`);
    }
    links.push(...definitions.values());
    for (const link of links) {
      if (/^https?:/i.test(link)) {
        if (
          /^https:\/\/github\.com\/hortinis\//.test(link) &&
          !crossRepositoryUrls.has(link)
        )
          report(file, `cross-repository URL is not allowlisted: ${link}`);
        continue;
      }
      if (/^[a-z][a-z\d+.-]*:/i.test(link)) continue;
      const [pathname, fragment] = link.split("#");
      let target;
      let anchor;
      try {
        target = pathname
          ? resolve(
              root,
              dirname(file),
              decodeURIComponent(pathname.split("?")[0]),
            )
          : resolve(root, file);
        anchor =
          fragment === undefined ? undefined : decodeURIComponent(fragment);
      } catch {
        report(file, `invalid link encoding: ${link}`);
        continue;
      }
      if (relative(root, target).startsWith("..") || !existsSync(target)) {
        report(file, `missing or outside-repository link: ${link}`);
      } else if (
        anchor &&
        target.endsWith(".md") &&
        !anchors(readFileSync(target, "utf8")).has(anchor)
      ) {
        report(file, `missing heading anchor: ${link}`);
      }
    }
    if (/^docs\/architecture\/decisions\/\d{4}-.*\.md$/.test(file)) {
      if (!/^- Status:\s*\S.+$/m.test(text))
        report(file, "missing ADR Status header");
      const date = text.match(/^- Date: (\d{4}-\d{2}-\d{2})\s*$/m)?.[1];
      if (
        !date ||
        Number.isNaN(Date.parse(date)) ||
        new Date(date).toISOString().slice(0, 10) !== date
      )
        report(file, "missing or invalid ADR Date header");
      const index = resolve(root, "docs/architecture/README.md");
      if (
        !existsSync(index) ||
        !readFileSync(index, "utf8").includes(
          `](decisions/${file.split("/").at(-1)})`,
        )
      )
        report(file, "ADR is absent from architecture index");
    }
  }
  return errors;
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const root = resolve(fileURLToPath(new URL("../..", import.meta.url)));
  const files = [
    ...new Set(
      execFileSync(
        "git",
        ["ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        { cwd: root, encoding: "utf8" },
      )
        .split("\0")
        .filter(Boolean),
    ),
  ];
  const errors = checkDocumentation(root, files);
  if (errors.length) {
    console.error(errors.join("\n"));
    process.exitCode = 1;
  } else
    console.log(
      "Documentation links, anchors, ADR headers, and ADR index passed.",
    );
}
