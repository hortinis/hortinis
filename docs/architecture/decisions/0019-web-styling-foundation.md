# ADR-0019: Web styling foundation

- Status: Accepted
- Date: 2026-09-09

## Context

The Angular application needs a predictable styling baseline that supports component-local styles, future theming, and maintainable class naming without introducing a runtime styling dependency.

Native CSS supports nesting but does not support Sass-style selector concatenation. A future component-library decision may also require build-time theme generation. Angular Material is one possible option, but it has not been selected.

## Decision

- Author Angular component and global styles using SCSS syntax.
- Compile SCSS to CSS through the Angular build toolchain; do not add a browser runtime dependency for styling.
- Retain Angular's default emulated view encapsulation for component styles.
- Treat an Angular component as the primary styling boundary.
- Use lowercase kebab-case class names that describe semantic roles rather than visual appearance.
- Use concise component-local classes by default; strict BEM naming is not mandatory.
- Represent reusable runtime theme values with CSS custom properties.
- Keep global styles limited to application-wide tokens, element defaults, resets, and deliberately shared utilities.
- Do not target Angular component internals through `::ng-deep`.
- Do not select Angular Material, another component library, or a utility CSS framework through this decision.

## Alternatives considered

### Use plain CSS only

This avoids preprocessing and is sufficient for many component styles. It does not provide Sass functions, mixins, selector concatenation, or compatibility with Sass-based theme-generation APIs.

### Require strict BEM naming

BEM provides explicit global namespaces and predictable element and modifier names. Angular's component style encapsulation already provides a local boundary, so requiring the full block name on every local class would add repetition without equivalent benefit.

### Adopt a utility CSS framework

A utility framework could provide a predefined styling system, but it would add another dependency and impose conventions before the application's design-system requirements are known.

## Consequences

- Sass is part of the frontend build dependency graph and is pinned by the workspace lockfile.
- Most component SCSS should remain ordinary CSS where preprocessing adds no value.
- Component styles remain locally scoped, while global tokens can cross component boundaries through CSS custom-property inheritance.
- A future component library and its theming model require a separate decision and implementation increment.
- The browser receives generated CSS and requires no Sass runtime.

## Validation criteria

- Development and production Angular builds compile the SCSS entry point and component styles.
- Generated browser assets contain CSS rather than Sass source requirements.
- New component styles follow semantic English naming and remain within their component boundary unless explicitly global.
- No third-party component library, runtime stylesheet service, or external runtime asset is introduced by this decision.
