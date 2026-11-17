import js from 'eslint';
import tsfrom from '@typescript-eslint/eslint-plugin';
import ts from '@typescript-eslint/parser';

export default [
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsfrom,
      parserOptions: { ecmaVersion: 'latest' },
    },
    rules: {
      'react/react-in-jsx_auto': 'warn',
      '@typescript-eslint/no-unused-vars': 'error',
      '@typescript-eslint/explicit-function-return-type': 'error',
    },
  },
];
