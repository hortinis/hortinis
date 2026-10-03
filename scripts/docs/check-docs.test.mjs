import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import assert from "node:assert/strict";
import { checkDocumentation } from "./check-docs.mjs";

function fixture(t, contents) {
  const root = mkdtempSync(join(tmpdir(), "hortinis-docs-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const [file, text] of Object.entries(contents)) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    writeFileSync(join(root, file), text);
  }
  return checkDocumentation(root, Object.keys(contents));
}

const adr = "docs/architecture/decisions/0001-example.md";
const validAdr =
  "# ADR-0001: Example\n\n- Status: Accepted\n- Date: 2026-10-03\n";
const index = {
  "docs/architecture/README.md": "[Example](decisions/0001-example.md)",
};

test("accepts relative links, encoded paths, duplicate anchors, and reference links", (t) => {
  assert.deepEqual(
    fixture(t, {
      "README.md":
        "[One](docs/page.md#hello-world) [Two](docs/page.md#hello-world-1)\n[Reference][page]\n\n[page]: docs/a%20b.md\n",
      "docs/page.md": "# Hello, world!\n# Hello, world!\n",
      "docs/a b.md": "# Other\n",
      [adr]: validAdr,
      ...index,
    }),
    [],
  );
});

test("rejects missing targets, missing anchors, and undefined references", (t) => {
  const errors = fixture(t, {
    "README.md":
      "# Home\n[Broken](missing.md) [Anchor](#absent) [Ref][undefined]\n",
  });
  assert.equal(errors.length, 3);
  assert.ok(errors.some((error) => error.includes("missing heading anchor")));
  assert.ok(errors.some((error) => error.includes("undefined link reference")));
});

test("rejects sibling-checkout links even when their destinations exist", (t) => {
  assert.match(
    fixture(t, { "README.md": "[Sibling](../README.md)" })[0],
    /outside-repository/,
  );
});

test("ignores illustrative links inside fenced code", (t) => {
  assert.deepEqual(
    fixture(t, { "README.md": "```md\n[Example](missing.md)\n```\n" }),
    [],
  );
});

test("requires ADR Status, a valid calendar Date, and architecture index membership", (t) => {
  const errors = fixture(t, { [adr]: "# Example\n- Date: 2026-02-30\n" });
  assert.equal(errors.length, 3);
  assert.ok(errors.some((error) => error.includes("Status")));
  assert.ok(errors.some((error) => error.includes("Date")));
  assert.ok(errors.some((error) => error.includes("index")));
});

test("allows reviewed cross-repository URLs and rejects unreviewed ones without network requests", (t) => {
  const errors = fixture(t, {
    "README.md":
      "[Approved](https://github.com/hortinis/hortinis-plants/blob/main/docs/development/implementation-plan.md)\n[Unapproved](https://github.com/hortinis/other)\n[Reference](https://example.com/docs)",
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /not allowlisted/);
});
