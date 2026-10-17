import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const register = readFileSync(
  new URL("../../docs/security/asvs-5.0.0-applicability.md", import.meta.url),
  "utf8",
);
const rows = [
  ...register.matchAll(
    /^\| `v5\.0\.0-([\d.]+)` \| ([123]) \| ([^|]+) \| ([A-Z_0-9]+) \|$/gm,
  ),
];

test("ASVS register keeps all 345 unique requirements, valid rationale keys and reconciled counts", () => {
  assert.equal(rows.length, 345);
  assert.equal(new Set(rows.map((r) => r[1])).size, 345);
  const keys = new Set(
    [...register.matchAll(/^\| ([A-Z_0-9]+) \|/gm)].map((r) => r[1]),
  );
  const counts = new Map();
  const levels = new Map();
  for (const [, id, level, disposition, rationale] of rows) {
    const status = disposition.trim();
    assert.ok(keys.has(rationale), id + ": unknown rationale " + rationale);
    counts.set(status, (counts.get(status) ?? 0) + 1);
    levels.set(level, (levels.get(level) ?? 0) + 1);
  }
  assert.deepEqual(Object.fromEntries(levels), { 1: 70, 2: 183, 3: 92 });
  const declared = [
    ...register.matchAll(
      /^\| (Applicable|Selected L3|Conditional|Pending decision|Deviation|N\/A|Outside L2) \| [^|]+ \| (\d+) \|$/gm,
    ),
  ];
  assert.equal(declared.length, 7);
  for (const [, status, count] of declared)
    assert.equal(counts.get(status) ?? 0, Number(count), status);
});

test("R1 retains strength/session applicability and remaps mail/action-token controls", () => {
  const row = (id) => rows.find((r) => r[1] === id);
  for (const id of [
    "1.3.11",
    "6.3.3",
    "6.5.1",
    "6.5.2",
    "6.5.3",
    "6.5.4",
    "6.5.5",
    "6.6.2",
    "6.6.3",
    "7.1.1",
    "7.1.2",
    "7.3.1",
    "7.3.2",
  ])
    assert.equal(row(id)?.[3].trim(), "Applicable", id);
  assert.equal(row("6.4.4")?.[3].trim(), "Conditional");
  assert.equal(row("6.3.3")?.[4], "STRENGTH");
});
