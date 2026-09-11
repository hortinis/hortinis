# V0 cross-repository dependency graph

- Scope: technical and product work required to complete the Hortinis V0 product-validation objective,
  including the local-validation artifact produced by `hortinis-plants`.
- Sources: [technical foundation plan](foundation-implementation-plan.md),
  [product implementation plan](product-implementation-plan.md), and the
  [`hortinis-plants` catalog implementation plan](../../../hortinis-plants/docs/development/implementation-plan.md).
- Legend: `validated` is complete; `planned` and `in progress` still require completion; an arrow means the
  source is a prerequisite of the target.

## 1. Completion graph

```text
hortinis-plants V1.4 + P0.7a + E10   P0.2a + P0.4a + P0.5a + E10
                  |                                  |
                  v                                  v
         V0.1 Catalog import                V0.2 Synchronized records
                  |                                  |
                  +----------------+-----------------+
                                   |
                                   v
                        V0.3 Integrated V0 test
                                   |
                                   v
                              HORTINIS V0
```

V0 is complete only when V0.3 is validated. V0.1 and V0.2 may proceed independently after their
respective prerequisites and converge at V0.3.

## 2. Product preparation

Product specifications can progress in parallel with the technical foundation.

```text
P0.1 Establish product tracker
 |
 +--> P0.2a Define the first-test journey
 |
 +--> P0.4a Define the V0 business contracts
 |      |
 |      +--> P0.5a Define the V0 synchronization scope
 |
 +--> P0.7a Define the local catalog-validation contract
```

The tasks produce the following prerequisites:

- P0.2a defines the minimum screens and the visible local, pending, synchronized, failed, and conflict
  states.
- P0.4a defines the garden, plant-selection, and cultivation-plan records and operations.
- P0.5a maps those operations to the synchronization protocol, including ordering, revisions, and the
  test-only synchronization scope.
- P0.7a defines the exact plants, catalog fields, rules, provenance, and fixtures required from
  `hortinis-plants`.

## 3. Hortinis technical path to E10

### 3.1 Validated baseline

```text
A1 --> A2 --> A3 --> A5
       |             ^
       +----> A4 ----+

A3 --> B1 --> B2
        |
        +----> B3
```

A1 through A5 and B1 through B3 are validated.

### 3.2 Frontend lane

```text
B1 + B2 + B3
      |
      +--> B4 PWA shell and offline reload

B2
 +--> B8 Dexie schema, migrations, transactions, and test isolation
       |
       +--> B7 Web architecture and boundary checks
       |
       +--> E2 Atomic local projection and outbox

B2 + D2
 +--> B9 HTTP synchronization adapter
```

### 3.3 Backend and database lane

```text
A4
 +--> C5 Spring Boot service
       |
       +--> C6 Health, readiness, and safe logging
       |
       +--> C7 Backend quality and architecture checks
       |
       +--> F1 Minimal Compose topology
       |
       +--> D2 Technical synchronization envelopes <-- D1 Contract validation

C5 + D2
 +--> D3 PostgreSQL persistence
       |
       +--> D4 PostgreSQL development topology <-- F1
             |
             +--> D5 Flyway technical migration
                   |
                   +--> D6 PostgreSQL integration tests
```

### 3.4 Synchronization lane

```text
D2
 +--> E1 Technical conformance fixtures
       |
       +--> E2 Atomic local state and outbox
             |
             +--> E3 Push and atomic server acceptance <-- B9 + D5 + D6
                   |
                   +--> E4 Idempotent retry
                   |     |
                   |     +--> E4a Dependent operation chain
                   |
                   +--> E5 Pull through an opaque cursor
                         |
                         +--> E6 Browser-reload recovery
                         |
                         +--> E7 Expected-revision conflict

E2 + B4
 +--> E8 Offline and service-failure independence

E1 through E8
 +--> E9 Cross-runtime conformance
```

### 3.5 Integrated topology and V0 technical gate

```text
B1 + C5 + F1
 +--> F3 Same-origin browser and API validation

B4
B7 + B8 + B9
C6 + C7
D1 through D6
E1 through E9
F1 + F3
 |
 +--> E10 V0 technical readiness report
```

E10 is the technical authorization for V0 product implementation. It must trace every V0 technical gate
requirement to passing evidence.

## 4. `hortinis-plants` path to the V0 artifact

V0 uses the smaller, non-publishable `dev-validation` artifact. It does not require the complete
production `fr-mvp` catalog.

### 4.1 Validated catalog baseline

The following `hortinis-plants` capabilities are validated:

- C0 catalog decisions;
- C1.1 project bootstrap;
- C1.2 formatting and linting;
- C1.3 test foundation;
- C1.5 schema compilation;
- C1.6 validation API;
- C1.7a critical conformance fixtures;
- C1.8 canonical JSON;
- C1.10a deterministic gzip, SHA-256, byte-size, and entry-count behavior;
- C1.11 minimal source-manifest boundary; and
- V1.1 minimum consumer contracts.

The direct V1.1 dependency is:

```text
C1.5 + C1.6 + C1.7a
 +--> V1.1 Minimum consumer contracts
```

### 4.2 Remaining cross-repository catalog path

```text
Hortinis P0.1
 |
 +--> Hortinis P0.7a Accepted dev-validation scope
       |
       +------------------------------+
                                      v
hortinis-plants V1.1 ----------> V1.2 Curated validation dataset
                                      |
                                      v
                    C1.8 + C1.10a --> V1.3 Local artifact builder
                                      |
                                      v
                              V1.4 Hortinis conformance handoff
                                      |
                         +------------+------------+
                         |                         |
                         v                         v
                Versioned schemas          Generated local artifact
                and small fixtures         for explicit selection
                         |                         |
                         +------------+------------+
                                      |
                                      v
                         Hortinis V0.1 catalog import
```

V1.4 must provide:

- the generated `dev-validation` artifact;
- the schemas Hortinis must pin;
- valid and invalid consumer fixtures;
- corrupt-hash and incomplete-artifact fixtures;
- an unsupported-version fixture; and
- matching schema and fixture version identifiers across both repositories.

The following `hortinis-plants` tasks are not V0 prerequisites:

- C1.4 complete continuous integration;
- complete C1.7 conformance coverage;
- C1.9 bounded-memory streaming;
- C1.10b streaming hashing and compression;
- C1.12 the generic importer and run-manifest abstraction; and
- C2 through C6 production contracts, sources, curation, release, and integration.

## 5. Product implementation and final convergence

### 5.1 Catalog branch

```text
P0.7a + hortinis-plants V1.4 + E10
 +--> V0.1 Local catalog acquisition
```

V0.1 pins the consumer schemas and fixtures, imports through the catalog source boundary, validates the
manifest and artifacts, stages and atomically activates the catalog in Dexie, exposes import failures, and
proves offline lookup without replacing a valid active catalog after failure.

### 5.2 Business and synchronization branch

```text
P0.2a + P0.4a + P0.5a + E10
 +--> V0.2 First synchronized business records
```

V0.2 defines the V0 contracts before adapters, adds matching TypeScript and Java rules, Dexie and Flyway
migrations, atomic local outbox and server acceptance transactions, synchronization UI states, shared
fixtures, and two-browser offline, reload, retry, ordering, and conflict validation.

### 5.3 V0 completion

```text
V0.1 + V0.2
 +--> V0.3 Integrated product test
       |
       +--> V0 COMPLETE
```

V0.3 adds the reviewed, versioned sowing or planting-window rule and validates explanation, limitation, or
abstention behavior through the complete two-browser, offline-reload, service-failure, missing-catalog, and
synchronization-state journey.

## 6. Critical path from the current state

```text
P0.1
 +--> P0.7a --> plants V1.2 --> V1.3 --> V1.4 --> V0.1 ----+
 |
 +--> P0.4a --> P0.5a ------------------------------+      |
 |                                                   |      |
 +--> P0.2a -----------------------------------------+      |
                                                     v      |
Remaining Hortinis foundation work --------------> E10 --> V0.2
                                                            |
                                               V0.1 + V0.2 -+
                                                            |
                                                            v
                                                           V0.3
```

The product specifications, `hortinis-plants` V1 artifact work, and the Hortinis technical foundation are
parallel workstreams. They converge only after E10 and the V1.4 handoff are complete.

## 7. Cross-plan dependency clarification

`hortinis-plants` V1.4 is a hard cross-repository prerequisite for Hortinis V0.1 because V0.1 cannot run
its acquisition and failure scenarios without the artifact, pinned schemas, and conformance fixtures.
Until that dependency is added directly to the product implementation plan, this graph records the
combined execution requirement explicitly.
