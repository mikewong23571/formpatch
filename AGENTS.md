# Repository Guidelines

## Project Structure & Module Organization
`formpatch` is a small Clojure/Babashka CLI. Core editing logic lives in `src/formpatch/core.clj`; command parsing and JSON output live in `src/formpatch/cli.clj`; the main entry namespace is `src/formpatch.clj`. Tests are split by layer in `test/formpatch_test.clj` and `test/formpatch_cli_test.clj`. Supporting docs live in `README.md`, `doc/`, and `rewrite-clj-object-editing-design.md`. Keep agent-facing skill material under `skills/formpatch/`. Use `resources/` only for runtime assets that must ship with the tool.

## Build, Test, and Development Commands
Use the existing entrypoints rather than ad hoc scripts:

- `./bin/formpatch --help`: run the local wrapper exactly as contributors will use it.
- `bb formpatch --help`: run the Babashka task from `bb.edn`.
- `bb -m formpatch.cli/-main --help`: exercise the installed-tool entrypoint directly.
- `clojure -T:build test`: run the full JVM test suite with `cognitect.test-runner`.
- `clojure -T:build ci`: run tests, compile, and build the uberjar in `target/`.
- `bbin install . --as formpatch`: install the tool from the current checkout for local verification.

## Coding Style & Naming Conventions
Follow the existing Clojure style in `src/`: two-space indentation, aligned bindings, kebab-case vars/functions, and namespace names that mirror file paths. Keep functions small and data-oriented. Prefer `defn-` for private helpers and descriptive keywords such as `:file-rev` and `:invalid-handle`. No formatter is configured here, so preserve surrounding layout and avoid unrelated whitespace churn.

## Testing Guidelines
Add or update tests for every behavior change in both core logic and CLI output when applicable. Place tests in `test/*_test.clj`, name `deftest` blocks by behavior, and cover error cases as well as happy paths. For CLI changes, assert machine-readable JSON fields and exit codes, not just printed text.

## Commit & Pull Request Guidelines
Match the current history: short, imperative, capitalized subjects such as `Add formpatch skill` or `Refine formpatch object workflow`. Keep commits focused. Pull requests should describe the user-visible behavior change, list the commands run for verification, and update `README.md` or `CHANGELOG.md` when CLI behavior or install steps change.

## Agent-Specific Notes
This repository is built for agent-driven editing. Preserve stable handle semantics (`oid`, `rev`, `file_rev`), keep JSON output machine-friendly, and avoid changes that weaken deterministic top-level formatting or optimistic locking behavior.
