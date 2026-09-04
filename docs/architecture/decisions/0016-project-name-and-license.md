# ADR-0016: Project name and license

- Status: Accepted

## Context

The initial documentation used the name Hortisys while the repository and project name are Hortinis. The repository already contains the GNU Affero General Public License version 3, while its README incorrectly said that no license had been selected.

For software that can be operated as a network service, a permissive license would allow third parties to offer proprietary modified versions without publishing those modifications.

## Decision

- Use `Hortinis` as the product name and `hortinis` as the canonical package, image, and repository namespace.
- License the project under GNU Affero General Public License version 3 only, using the SPDX identifier `AGPL-3.0-only`.

## Consequences

- Modified versions offered to users over a network remain subject to the AGPL source-availability obligations.
- Hortinis itself can be operated as a hosted service under the same license.
- Dependencies must be checked for compatibility with AGPL-3.0-only.
- Any future dual-licensing strategy requires a separate legal and governance decision.

