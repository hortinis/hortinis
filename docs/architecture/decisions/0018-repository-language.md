# ADR-0018: Repository language

- Status: Accepted
- Date: 2026-09-05

## Context

Hortinis is an open-source project intended to be understandable and maintainable by contributors beyond its initial French-speaking discussions. Some product documents were initially written in French while architecture and repository guidance were in English.

A single repository language keeps requirements, code, and operational guidance consistent and avoids parallel versions drifting apart.

## Decision

- Write all repository content in English.
- Apply this rule to documentation, architecture records, specifications, contributor and agent instructions, source identifiers, comments, tests and fixtures, configuration descriptions, scripts, examples, prompts, and repository-authored interface and diagnostic text.
- Use English words for descriptive file and directory names.
- Translate material from discussions in other languages before adding it to the repository, preserving its meaning and the distinction between accepted decisions and proposals.
- Maintain one English source of truth rather than parallel English and non-English copies.
- Apply the rule during review of every addition and change. Keep `AGENTS.md` and development validation guidance aligned with it.

This rule governs repository content. It does not require conversations with maintainers or gardeners' runtime data to be in English, and it does not change the initial geographical coverage of the product.

## Alternatives considered

### Allow mixed-language repository content

This reduces immediate translation effort but fragments terminology and makes contribution and review depend on the language of the original discussion.

### Maintain parallel translations of all documents

This improves access for some readers but duplicates maintenance and creates ambiguity when the versions diverge.

## Consequences

- Contributors and agents use English consistently across product, implementation, and operations.
- Source discussions may still take place in another language, but committed artifacts must be translated.
- Proper names, stable identifiers, and externally defined technical symbols retain their identity; the language rule is not a reason to rewrite a protocol or data identifier.
- Language review is a content check. Searching for non-ASCII characters alone cannot establish whether text is English.

## Migration impact

- Translate the two initial product documents into English and rename them to `docs/product/functional-decisions.md` and `docs/product/open-questions.md`.
- Update all references to their previous paths and remove the superseded copies.
- Preserve their decision identifiers and accepted requirements during translation.
- Write the new `docs/product/product-direction.md` in English and separate exploratory ideas from release commitments.
- No application data, public runtime contract, or selected technology changes as a result of this decision.

## Validation criteria

- Review added and modified content for English wording, including filenames, examples, and embedded prompts.
- Verify that translation preserves accepted decisions and unresolved questions.
- Check that renamed documentation has no stale references and all relative links and anchors resolve.
- Run the pre-scaffold checks documented in `docs/development/README.md` until executable validation tooling exists.
