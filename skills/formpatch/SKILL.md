---
name: formpatch
description: Inspect and edit Clojure source files with the `formpatch` CLI. Use by default whenever Codex needs to inspect, create, update, or delete code in `.clj`, `.cljc`, or `.cljs` files and the work can be expressed as whole top-level object edits. Use it to list top-level objects, insert new top-level forms, replace existing top-level forms, delete top-level forms, or explain the `snapshot` + `id` + `hash` workflow used by Formpatch. Prefer this skill over manual text patches for normal Clojure editing unless the task specifically requires zipper-level internal surgery or another specialized refactor tool.
---

# Formpatch

## Overview

Use `formpatch` as the default path for normal Clojure editing. It is a fast Babashka CLI for top-level Clojure edits and treats a file as an ordered list of top-level objects. It supports:

- `list`: inspect top-level objects
- `insert`: add one or more top-level objects before or after an anchor
- `replace`: replace one or more contiguous top-level objects with zero or more new top-level objects

Use whole-object rewrites. Do not try to patch inside a form with this skill.

## Workflow

1. Run `formpatch list --file <path>` and inspect the returned JSON.
2. Pick the target object's `id` and `hash` from the current `snapshot`.
3. Draft full replacement forms.
4. Prefer `--dry-run --diff` before mutating.
5. Run `insert` or `replace`.
6. Re-run `list` after every successful mutation because the old `snapshot`, `id`, and `hash` are stale.

## Query

List top-level objects:

```bash
formpatch list --file src/foo/core.clj
```

Interpret the response as:

- `snapshot`: file version for the next mutation
- `id`: top-level object index within that snapshot
- `hash`: optimistic lock for that object
- `text`: full source text of the object
- `head` / `name`: best-effort metadata for display and selection

## Create

Insert new top-level forms with raw `stdin`.

Insert after an object:

```bash
formpatch insert \
  --file src/foo/core.clj \
  --snapshot <snapshot> \
  --after <id>:<hash> \
  --dry-run \
  --diff <<'EOF'
(defn helper-a
  []
  :a)

(defn helper-b
  []
  :b)
EOF
```

Remove `--dry-run --diff` to apply the edit.

Use `--before` instead of `--after` when needed.

## Update

Replace one object by rewriting the full form:

```bash
formpatch replace \
  --file src/foo/core.clj \
  --snapshot <snapshot> \
  --targets <id>:<hash> \
  --dry-run \
  --diff <<'EOF'
(defn updated
  [x]
  (inc x))
EOF
```

Replace one object with multiple objects by passing multiple top-level forms on `stdin`.

Replace multiple objects only when the target ids are contiguous.

## Delete

Delete with `replace --empty`:

```bash
formpatch replace \
  --file src/foo/core.clj \
  --snapshot <snapshot> \
  --targets <id>:<hash> \
  --empty
```

Delete multiple objects only when the target ids are contiguous.

## Guardrails

- Treat `snapshot`, `id`, and `hash` as a short-lived handle. Re-query after each successful mutation.
- Start with this skill for ordinary Clojure editing. Escalate to lower-level `rewrite-clj` work only when whole-object replacement is not sufficient.
- Keep edits at top-level object granularity. If only part of a function changes, still replace the full function.
- Use `--dry-run --diff` before writing unless the change is trivial.
- Pass raw top-level forms through `stdin`. Do not wrap replacement code in JSON strings.
- Expect JSON on `stdout` for success and machine-readable JSON on `stderr` for failure.
- Use the globally installed `formpatch` command by default. If it is unavailable, fall back to the repo-local entrypoint such as `./bin/formpatch` or `bb formpatch`.
