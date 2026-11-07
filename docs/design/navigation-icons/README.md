# Navigation icons

Five custom outlined SVGs based on the approved concepts: a plain house, two garden sprouts, a calendar containing a leaf, an open journal containing a leaf and writing lines, and a botanical speech bubble for the quick-note action. These are custom assets inspired by rounded Material icon geometry, not official Material Symbols.

[Preview at enlarged, 32 px, and 24 px sizes](preview.svg).

[Quick-note preview](note-preview.svg).

## Assets

- [Home](../../../public/icons/navigation/home.svg)
- [Garden](../../../public/icons/navigation/garden.svg)
- [Schedule](../../../public/icons/navigation/schedule.svg)
- [Diary](../../../public/icons/navigation/diary.svg)
- [Quick note](../../../public/icons/navigation/note.svg)

Each asset uses a `0 0 24 24` viewBox, a 1-unit stroke, rounded caps and joins, a transparent background, and `currentColor`. No fonts or external resources are required. Keep the square aspect ratio. Color is controlled by the containing element. Artwork bounds are centered at `(12, 12)`; small vertical translations balance the canvas margins. Navigation uses square 24 px icon containers with no inherited text-line minimum height.

## Angular Material integration

The assets are ready for integration; application registration and navigation wiring are not changed by this delivery.

Following the [Angular Material icon documentation](https://material.angular.dev/components/icon/overview), register the five local URLs once in an application initializer. The application already provides `HttpClient`. Import `MatIconRegistry` from `@angular/material/icon`, `DomSanitizer` from `@angular/platform-browser`, and `inject` and `provideAppInitializer` from `@angular/core`:

```typescript
provideAppInitializer(() => {
  const registry = inject(MatIconRegistry);
  const sanitizer = inject(DomSanitizer);

  for (const name of ['home', 'garden', 'schedule', 'diary', 'note']) {
    registry.addSvgIconInNamespace(
      'hortinis',
      name,
      sanitizer.bypassSecurityTrustResourceUrl(`icons/navigation/${name}.svg`),
    );
  }
});
```

Only trust these fixed, repository-owned asset URLs, never user-provided values. Add the initializer to application providers and import `MatIcon` in the consuming standalone component:

```html
<mat-icon svgIcon="hortinis:home" />
<mat-icon svgIcon="hortinis:garden" />
<mat-icon svgIcon="hortinis:schedule" />
<mat-icon svgIcon="hortinis:diary" />
<mat-icon svgIcon="hortinis:note" />
```

Keep visible navigation labels alongside the decorative icons. For an icon-only button, give the button an accessible label. No Material Symbols font class is needed for these SVGs.

When wiring navigation, prefetch `/icons/navigation/*.svg` in the service worker application asset group so every navigation icon is available after service worker installation, before its first use offline. The existing general asset group loads assets lazily. Verify the five icons with an offline reload after integration.
