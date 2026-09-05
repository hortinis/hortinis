# ADR-0017: Privacy-preserving web analytics

- Status: Accepted

## Context

Hortinis needs technical and product statistics to understand usage, diagnose navigation and performance problems, and improve the user experience. This need applies to the official website, the PWA, and autonomous self-hosted deployments.

ADR-0015 originally required Hortinis to ship without analytics by default. That rule prevented even a local, first-party measurement system that does not identify visitors. This decision revises only that part of ADR-0015 while preserving its restrictions on logging, external providers, user content, credentials, and operational observability.

Self-hosted operators also need the option to contribute anonymous statistics to the Hortinis project. These contributions can help the project understand adoption, deployment characteristics, feature usage, and performance outside the official deployment. That contribution must not turn self-hosted installations into sources of visitor-level telemetry or make a central Hortinis service necessary for normal operation.

## Decision

### Deployment and control

- Provide the same analytics engine, privacy guarantees, and compatible contracts for web surfaces served by a Hortinis deployment.
- Keep collection, storage, and reporting isolated by deployment. The official deployment and each self-hosted deployment have separate local statistics.
- Allow a dimension supplied by an optional infrastructure adapter to degrade to `unknown`; equivalent privacy guarantees do not require every deployment to have every dimension available.
- Enable local analytics by default and let the operator disable them completely.
- Provide visitors with clear information about analytics and an accessible means to object where the applicable legal basis requires it.
- A preference stored solely to remember an analytics objection is not an analytics identifier and must not be reused for any other purpose.
- Keep collection and reporting local to the deployment. A self-hosted installation must operate fully without contacting Hortinis analytics infrastructure.
- Keep contribution from a self-hosted installation to the Hortinis project disabled by default. Enable it only through an explicit operator action, and allow the operator to disable it again.

### Architecture

- Implement browser instrumentation in Angular, ingestion and aggregation in Spring Boot, and aggregate persistence in PostgreSQL.
- Define the ingestion and contribution contracts with OpenAPI 3.1 before implementing their adapters.
- Isolate analytics from the domain model and user-owned garden data behind application interfaces and replaceable infrastructure adapters.
- Bundle analytics code with the application. Do not load third-party analytics scripts or other third-party runtime assets.
- Do not persist or queue analytics events in IndexedDB, a service worker, or another browser store. Discard measurements that cannot be delivered while offline.
- Treat source addresses and standard HTTP request headers as transport metadata that infrastructure may process transiently to receive and protect a request. Do not copy them into analytics payloads, application models, persistence, or logs.

### Automated traffic

- Do not use fingerprinting or persistent visitor identifiers to distinguish people from automated clients.
- Exclude a verified bot from human product-usage counters. It may be counted in a separate aggregate bot schema when that schema has a documented measurement need.
- Use only closed categories such as `verified-search`, `verified-ai`, `verified-other`, and `unknown`. A self-declared user-agent without independent verification must not be classified as verified.
- Perform verification transiently behind a replaceable infrastructure interface. An adapter may use trusted reverse-proxy signals, cryptographic bot authentication, published operator IP ranges, or verified reverse DNS.
- Do not require Cloudflare for bot classification. A deployment using OVH or another provider may select another adapter or leave the category as `unknown`.
- Do not claim that `unknown` traffic is human. Product reports must document that unverified automation can remain in the statistics.

### Permitted measurements

The system may measure:

- page views using normalized route templates rather than concrete URLs;
- coarse country;
- language category;
- coarse, version-independent browser and platform categories;
- referrer category;
- performance measurements classified into predefined buckets in the browser before transmission and represented as aggregate counters, distributions, or histograms;
- predefined custom events and predefined, bounded event properties.

Referrers must be classified into a closed allowlist such as:

- `direct`;
- `internal`;
- `search:google`, `search:bing`, `search:duckduckgo`, `search:qwant`, and `search:ecosia`;
- `search:other`;
- explicitly allowlisted social sources and `social:other`;
- `external:other`;
- `unknown`.

The full referrer may be inspected transiently only to classify its hostname. The browser must transmit only the category. Search terms, paths, query parameters, and fragments must not be transmitted or persisted. Referrer statistics are approximate because referrers can be absent or falsified.

### Prohibited measurements

Do not include in analytics measurements or persist:

- analytics cookies, persistent visitor identifiers, session identifiers, or fingerprints;
- raw IP addresses or full user-agent strings;
- concrete URLs, query parameters, fragments, page titles, or route values containing user or domain identifiers;
- full referrers or search terms;
- free-form event names or properties;
- individual visitor journeys or data intended to recognize returning visitors;
- garden content, account data, precise locations, or other user-provided content.

IP addresses may be processed transiently by deployment infrastructure to derive coarse country information, but neither the analytics application nor its logs may persist them. Without an operator-selected country source, the country dimension must be `unknown`.

### Aggregation and retention

- Persist aggregate counters only. Do not persist individual analytics events or their exact timestamps.
- Define each counter schema as a reviewed allowlist of dimensions, buckets, and values. Do not support arbitrary combinations or exploratory joins over event-level data.
- Choose time buckets and dimension combinations according to a documented measurement need, using the least precise values that satisfy that need.
- Prevent combinations whose precision or sparsity could reconstruct a visit or single out a visitor. Merge, suppress, or widen sparse buckets when necessary.
- Do not infer that a bucket represents multiple visitors merely because it contains multiple events.
- Define and enforce a retention period and automatic purge rule for every aggregate schema before enabling it.
- Keep analytics storage and access separate from operational and security logs.

### Optional contribution to Hortinis

- Aggregate and anonymize statistics on the self-hosted installation before transmission.
- Transmit only versioned aggregates for fixed, non-overlapping contribution windows. Never transmit raw events, cumulative snapshots, or exact low-volume deltas.
- Permit the contributed counters to cover the same measurement categories as the local analytics, subject to the export anonymization rules.
- Do not transmit visitor IP addresses, event timestamps, sessions, journeys, concrete URLs, full referrers, search terms, or user-provided content.
- Show operators the categories of counters that will be contributed before they enable contribution.
- Keep installation authentication, delivery idempotency, and abuse prevention metadata separate from analytics counters. Such metadata may identify a deployment or operator but must never identify or correlate visitors.
- Do not claim that an aggregate is anonymous merely because it is a counter. Document and validate that the exported dimensions, bucket sizes, and handling of sparse values make visitor re-identification negligible.
- Prevent differencing and composition attacks across successive contributions. Each export schema must define suppression thresholds, release cadence, dimension combinations, and, where necessary, calibrated noise and a privacy budget.
- If anonymity cannot be established for an export schema, do not enable that schema without a new privacy and compliance review.
- The Hortinis receiver must not persist source IP addresses or request headers in application or access logs. Infrastructure may process them transiently to establish the connection and protect the service.

### Permitted use of contributed statistics

- Keep statistics produced by the official deployment separate from aggregates contributed by self-hosted deployments. A combined report must preserve and disclose the provenance of each population.
- Use contributed statistics to identify broad trends in application-version adoption, predefined feature usage, normalized-route usage, coarse performance buckets, and predefined technical success or failure counters.
- Use those trends to prioritize compatibility work, performance improvements, automated tests, documentation, and the review of features that appear underused or problematic.
- Report only sufficiently aggregated results. Do not provide drill-down, filtering, or exports that expose the activity of one installation or make one installation's contribution inferable.
- Describe the population as contributing installations or received contributions. Because contribution is optional, do not present it as representative of every Hortinis installation or user.
- Do not derive or claim unique visitors, returning visitors, individual journeys, the exact number of Hortinis users, or the exact number of deployed installations.
- Keep installation authentication and idempotency metadata outside analytics reporting. Counting active contributing installations is a separate purpose that requires explicit operator information, a documented legal basis, retention and deletion rules, and a new review before it is enabled.
- Do not use contributed statistics for advertising, profiling, ranking operators, comparing identifiable installations, or making decisions about an individual visitor or operator.
- Any public report must apply an additional disclosure review and aggregation step before publication.

### Infrastructure providers

- Cloudflare Free may be selected by an operator for DNS, CDN, security, and coarse country detection.
- Treat Cloudflare as an optional infrastructure processor and adapter, not as the behavioral analytics system.
- Do not send browser analytics directly to a Cloudflare analytics product.
- Document the operator's controller responsibilities, provider configuration, data-processing terms, relevant transfers, retention, and visitor information duties.
- Do not trust provider-specific country or bot headers from arbitrary clients. A provider adapter must document how it verifies the proxy provenance and removes or ignores spoofable inbound headers.

### Compliance boundary

- Design and operate analytics according to GDPR purpose limitation, data minimization, storage limitation, security, and accountability principles.
- Treat the CNIL audience-measurement exemption as a framework to assess and document, not as a compliance claim guaranteed by this architecture decision.
- Reassess the legal basis, information, objection or consent mechanism, anonymization, and controller or processor roles whenever purposes, measurements, infrastructure, recipients, or third-party services change.

## Alternatives considered

### Keep analytics entirely absent

This preserves the rule in ADR-0015 but provides insufficient evidence for product and performance improvements.

### Derive statistics from access logs

This duplicates operational and behavioral purposes, encourages retention of IP addresses and full URLs, and gives the application less control over minimization and categorization.

### Use an external analytics service

This reduces implementation effort but introduces an external runtime dependency and makes data reuse, transfers, self-hosting, and provider replacement harder to control.

### Send raw events from every deployment to Hortinis

This would provide flexible centralized analysis but would create visitor-level telemetry, weaken autonomous self-hosting, and substantially increase privacy and compliance risk.

## Consequences

- Operators receive useful local product and performance statistics without tracking visitors across sessions.
- Returning unique visitors, cross-session attribution, and individual journeys are intentionally unavailable.
- Local analytics are active without operator setup, but both operators and visitors retain the controls defined above.
- Self-hosted operators may voluntarily contribute useful statistics without sending event-level visitor data.
- Project reports can use contributed aggregates to identify broad adoption, feature-usage, and performance trends, but the voluntary sample is not representative of all Hortinis installations or users.
- Aggregation schemas require up-front design and cannot be expanded by arbitrary database queries.
- Very small or precise contributed buckets may need to be merged, delayed, or suppressed.
- Analytics require dedicated retention, purge, disclosure, access-control, and verification work.
- Cloudflare can improve country classification but is never required for application operation.

## Relationship to existing decisions

This decision supersedes only the following parts of ADR-0015:

- the requirement to ship without analytics by default;
- the consequence that the default application performs no optional tracking.

The remaining privacy and observability decisions in ADR-0015 continue to apply. The repository guidance distinguishes default local privacy-preserving analytics from prohibited telemetry, optional trackers, and third-party analytics.

## Migration impact

- Existing deployments must receive an explicit release note and updated privacy information before local analytics are introduced.
- The analytics database schema must be additive, isolated from domain data, and removable without affecting application operation.
- Disabling analytics must stop new collection immediately; retained aggregates remain subject to their documented purge policy and must be removable by the operator.
- Central contribution must remain disabled during upgrades unless the operator explicitly enabled it previously and the exported schema and purposes have not materially changed.
- A material change to contributed categories or purposes requires renewed operator information and confirmation.

## Finalization gates

Acceptance of this decision establishes the privacy boundary but does not authorize analytics collection or contribution by itself. Before enabling local analytics, the project must:

- define the first local aggregate schemas, their measurement needs, browser-side buckets, allowed dimension combinations, retention periods, and purge rules in a follow-up decision;
- define the deployment trust boundary for source addresses, standard request headers, optional country classification, optional bot verification, reverse proxies, and access logs;
- document the information and objection experience and complete the applicable legal-basis and CNIL audience-measurement self-assessment;
- keep ADR-0015 and the repository guidance consistent with this decision so that local privacy-preserving analytics remain distinguished from prohibited telemetry, optional trackers, and third-party analytics.

Before enabling contribution from self-hosted deployments, the project must additionally:

- define a versioned export schema, fixed non-overlapping windows, contribution cadence, suppression rules, and defenses against differencing and repeated-release composition;
- document whether calibrated noise and a privacy budget are required and validate the selected mechanism against singling-out, linkability, and inference attacks;
- document the separate purpose, roles, legal basis, operator information, installation authentication, idempotency, abuse prevention, retention, and deletion behavior of the Hortinis receiver;
- obtain a privacy and compliance review confirming that exported data is anonymous before transmission or, if it is not anonymous, define the visitor-facing legal basis and controls required for that transmission;
- keep contribution disabled until all preceding gates have automated verification and operator documentation.

## Validation criteria

- Automated tests demonstrate that collection creates no analytics cookie, visitor identifier, session identifier, fingerprint, or browser-side analytics queue.
- Tests verify normalization of application routes and classification of every allowlisted, other, direct, internal, and missing referrer case.
- Tests verify that event names, properties, routes, dimensions, and values outside their allowlists are rejected.
- Tests verify that raw performance values are rejected and that only predefined browser-side performance buckets are accepted.
- Persistence tests demonstrate that only aggregate counters are stored and that raw IP addresses, full user-agent strings, full referrers, concrete URLs, and exact event timestamps are absent.
- Tests demonstrate that disabling local analytics stops collection and that a visitor objection is honored.
- Self-hosting tests demonstrate that no analytics request leaves the installation while contribution is disabled.
- Contribution tests demonstrate that only the documented versioned counters are sent, retries are idempotent, and visitor-level fields cannot enter the payload.
- Export validation covers sparse buckets, repeated-release differencing, composition across windows, and the documented anonymization rules for each contributed schema.
- Reporting tests keep official and contributed populations distinguishable, prevent installation-level drill-down, and preserve suppression rules after filtering or export.
- Published report templates identify the population as voluntary contributions and do not derive users, unique visitors, returning visitors, journeys, or total deployment counts.
- Retention and purge tests cover every aggregate and contribution metadata category.
- Log tests demonstrate that analytics endpoints do not retain prohibited fields or request bodies.
- Deployment tests demonstrate correct behavior without Cloudflare or any other external country provider.
- Bot-classification tests distinguish verified, merely declared, spoofed, and unknown traffic; tests also demonstrate correct operation without a bot-classification provider.

## References

- [CNIL: Cookies — solutions for audience measurement tools](https://www.cnil.fr/fr/cookies-et-autres-traceurs/regles/cookies-solutions-pour-les-outils-de-mesure-daudience)
- [CNIL: Audience-measurement consent-exemption self-assessment tool](https://www.cnil.fr/sites/default/files/2025-07/outil_d_auto-evaluation_mesure_d_audience.pdf)
- [CNIL: Anonymisation of personal data](https://www.cnil.fr/fr/technologies/lanonymisation-de-donnees-personnelles)
- [Cloudflare: HTTP headers and `CF-IPCountry`](https://developers.cloudflare.com/fundamentals/reference/http-headers/)
- [Cloudflare: Verified bots](https://developers.cloudflare.com/bots/concepts/bot/verified-bots/)
- [Cloudflare: Free bot-protection plan](https://developers.cloudflare.com/bots/plans/free/)
