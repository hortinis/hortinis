# Internal catalog preview

From the configured Hortinis home page, open **Internal catalog preview** and
choose **Import catalog preview**. Select all files in the catalog repository's
`artifacts/releases/reviewed-catalog/content-rehearsal/` directory together.
The reviewed rehearsal includes evidence with pending rights. The preview is
intended for internal inspection and carries no publication or commercial
clearance.

Open Hortinis over HTTPS or localhost so the browser can verify SHA-256
digests. The browser reads the selected files locally. After a successful import, use
**Browse subjects** to inspect each subject's own facts, rule parameters,
applicability and provenance. Growing types, cultivar groups and cultivars keep
their own identities and parent links. Source alternatives appear as individual
records. Rule parameters are displayed without garden or weather evaluation.

## Supported contract

[ADR-0014](../architecture/decisions/0014-plant-catalog-distribution.md) defines
operator selection as the trust entry point. SHA-256 checks establish integrity
relative to the selected manifest. The importer verifies the file set before
staging, then rechecks each artifact it rereads and parses the checked bytes in
memory. This keeps verification tied to the bytes used for import if a file
changes on disk. The preview supports flat file sets and the
`dev-validation` profile with schema version `1.0.0` and the exact pinned
consumer schema definitions. Stable `minimumConsumerVersion` values are compared
numerically with the Hortinis package version. Prerelease minimums and other
schema versions need explicit consumer support.

### Build input

Hortinis compiles standalone browser validators with Ajv from the catalog
repository's `artifacts/releases/reviewed-catalog/content-rehearsal/schemas.json`.
Set `HORTINIS_CATALOG_SCHEMA_BUNDLE` to the file's path before starting,
building, or testing Hortinis. An absolute path or a path relative to the
Hortinis repository root works. For CI, provision the schema bundle and set the
same variable.

```shell
HORTINIS_CATALOG_SCHEMA_BUNDLE=/path/to/schemas.json pnpm validate
```

The build checks the bundle's pinned SHA-256 before compiling the supported
consumer schemas and their transitive dependencies. An unset path, missing bundle, or digest
mismatch stops the build. Updating the build input requires reviewing and
updating the pin in [the schema bundle loader](../../scripts/catalog-schema-bundle.mjs),
including when an authoring schema changes the bundle's digest.

In the browser, the importer compares the selected bundle's supported
definitions structurally and verifies its hash against the selected manifest.
Other definitions in that bundle have no effect on the browser's compatibility
check. Browser operation uses the compiled validators offline.

The importer requires `manifest.json`, the declared `schemas.json` audit
bundle, the declared `source-manifests.json` array, and at least one plant
concept. Supported chunk families are taxon, taxonomic name, plant concept,
cultivar group, cultivar, localized name, cultivation context, geographic
context, plant fact, cultivation rule, relationship, evidence reference, source,
licence, review, and attribution. A selected optional artifact passes the same
checks as a required one. Other tagged audit JSON documents such as
`candidate-scope.json` are verified and parsed as metadata. Unreferenced
selected files are ignored. Source manifests are validated and stored for
provenance. Source record IDs refer to upstream data. Content admission and
review history can include historical authoring identifiers outside the live
consumer snapshot.

The file set is limited to 256 descriptors, a 256 KiB manifest, 8 MiB per
metadata file and 2 MiB per compressed chunk. Ingestion limits individual JSONL
records to 16 MiB, inflated chunks to 64 MiB and all inflated chunks to
128 MiB. Each record is schema-validated. Duplicate record IDs, mismatched
counts, dangling or incorrectly typed live record references, mismatched
source releases and invalid review-purpose references fail import. The
snapshot retains exact assertion scope and raw validated payloads.

## Activation and recovery

Validation writes bounded batches to isolated Dexie records keyed by staged
snapshot and catalog ID. Activation updates the active and previous pointers
in one transaction after the whole stage passes. The transaction also removes
the displaced older snapshot. Both the active snapshot and the previous
validated snapshot survive reloads, including offline reloads. **Restore
previous preview** swaps the two pointers. An invalid, cancelled or failed
import leaves the validated snapshots usable. A newly verified import replaces
any abandoned stage and fences out earlier writers. Catalog storage is separate
from the service worker's application-shell cache and from user garden records.

## Verification

`pnpm validate` runs the application and browser tests. The browser suite always
imports a clearly marked synthetic fixture built with the pinned schema input. It also imports the real rehearsal
when available at the sibling catalog repository path above. To use a different
location, set `HORTINIS_CATALOG_PREVIEW_DIR` to the directory containing the
full reviewed rehearsal. Playwright uses the product file input and IndexedDB
against the production build, then checks persistence and rollback offline.
Both suites keep catalog payloads out of the application source and build.
