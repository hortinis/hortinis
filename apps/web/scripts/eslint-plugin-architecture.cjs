module.exports = {
  rules: {
    'no-pure-rule-external-imports': {
      meta: {
        type: 'problem',
        docs: { description: 'Keep pure rules independent of external packages' },
        messages: {
          forbidden: 'Pure rules may only import other local modules through relative paths.',
        },
        schema: [],
      },
      create(context) {
        const checkSource = (node) => {
          if (typeof node.value === 'string' && !node.value.startsWith('.')) {
            context.report({ node, messageId: 'forbidden' });
          }
        };

        return {
          ImportDeclaration(node) {
            checkSource(node.source);
          },
          ExportAllDeclaration(node) {
            checkSource(node.source);
          },
          ExportNamedDeclaration(node) {
            if (node.source) checkSource(node.source);
          },
          ImportExpression(node) {
            checkSource(node.source);
          },
          CallExpression(node) {
            if (
              node.callee.type === 'Identifier' &&
              node.callee.name === 'require' &&
              node.arguments.length === 1
            ) {
              checkSource(node.arguments[0]);
            }
          },
        };
      },
    },
    'no-component-persistence-import': {
      meta: {
        type: 'problem',
        docs: { description: 'Prevent Angular components from importing persistence modules' },
        messages: {
          forbidden: 'Angular components must access persistence through application services.',
        },
        schema: [],
      },
      create(context) {
        const componentNames = new Set();
        const componentClasses = new Set();
        const persistenceImports = [];

        return {
          ImportDeclaration(node) {
            const source = node.source.value;
            if (source === '@angular/core') {
              for (const specifier of node.specifiers) {
                if (
                  specifier.type === 'ImportSpecifier' &&
                  (specifier.imported.name ?? specifier.imported.value) === 'Component'
                ) {
                  componentNames.add(specifier.local.name);
                }
              }
            }

            if (typeof source === 'string' && /(^|\/)persistence(\/|$)/.test(source)) {
              persistenceImports.push(node);
            }
          },
          Decorator(node) {
            const expression = node.expression;
            if (
              expression.type === 'CallExpression' &&
              expression.callee.type === 'Identifier' &&
              componentNames.has(expression.callee.name) &&
              node.parent.type === 'ClassDeclaration'
            ) {
              componentClasses.add(node.parent);
            }
          },
          'Program:exit'() {
            if (componentClasses.size === 0) return;
            for (const importNode of persistenceImports) {
              context.report({ node: importNode, messageId: 'forbidden' });
            }
          },
        };
      },
    },
    'no-sync-http-import': {
      meta: {
        type: 'problem',
        docs: { description: 'Keep synchronization rules independent of the HTTP adapter' },
        messages: {
          forbidden:
            'Only the synchronization HTTP adapter and its provider may import HTTP transport code.',
        },
        schema: [],
      },
      create(context) {
        const filename = context.filename.replaceAll('\\', '/');
        const isHttpAdapter = filename.endsWith('/http-synchronization-transport.ts');
        const isProvider = filename.endsWith('/synchronization-transport.token.ts');

        return {
          ImportDeclaration(node) {
            if (isHttpAdapter || isProvider) return;
            const source = node.source.value;
            if (
              source === '@angular/common/http' ||
              (typeof source === 'string' && source.includes('http-synchronization-transport'))
            ) {
              context.report({ node, messageId: 'forbidden' });
            }
          },
        };
      },
    },
  },
};
