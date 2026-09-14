// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');
const architecture = require('./scripts/eslint-plugin-architecture.cjs');

module.exports = defineConfig([
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    plugins: { architecture },
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        {
          type: 'attribute',
          prefix: 'hortinis',
          style: 'camelCase',
        },
      ],
      '@angular-eslint/component-selector': [
        'error',
        {
          type: 'element',
          prefix: 'hortinis',
          style: 'kebab-case',
        },
      ],
      'architecture/no-component-persistence-import': 'error',
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {},
  },
  {
    files: ['**/*.rule.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@angular/**',
                'dexie',
                'dexie/**',
                'rxjs',
                'rxjs/**',
                '@app/persistence/**',
                '@app/sync/**',
                '@app/providers/**',
                '**/persistence/**',
                '**/sync/**',
                '**/providers/**',
                'node:*',
                'fs',
                'fs/**',
                'path',
                'path/**',
              ],
              message:
                'Pure rules must not depend on frameworks, transport, persistence, filesystem, or providers.',
            },
          ],
        },
      ],
      'no-restricted-globals': [
        'error',
        'document',
        'fetch',
        'indexedDB',
        'localStorage',
        'navigator',
        'sessionStorage',
        'WebSocket',
        'XMLHttpRequest',
        'window',
      ],
      'architecture/no-pure-rule-external-imports': 'error',
    },
  },
]);
