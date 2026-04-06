---
name: formpatch
description: Use the `formpatch` CLI for top-level Clojure object edits when the user explicitly wants Formpatch, when `formpatch` is already installed in the target environment, or when developing this repository itself. Prefer the installed `formpatch` command for other repositories; use `./bin/formpatch` only while working inside the Formpatch repo. Use it to list top-level objects, fetch full objects, insert new top-level forms, replace existing top-level forms, delete top-level forms, or explain the `oid` + optional `rev` + optional `file_rev` workflow used by Formpatch.
---

# Formpatch

## Overview

Use `formpatch` when Formpatch is the requested or available editing path for top-level Clojure edits. It is a fast Babashka CLI that treats a file as an ordered list of top-level objects. It supports:

- `list`: inspect top-level objects with truncated text previews
- `get`: fetch one or more full top-level objects
- `insert`: add one or more top-level objects before or after an anchor, or at the head/tail of the file
- `replace`: replace one or more contiguous top-level objects with zero or more new top-level objects

Use whole-object rewrites. Do not try to patch inside a form with this skill.

## Command Selection

- When developing this repository, use the repo-local wrapper `./bin/formpatch`.
- In other repositories, prefer the installed `formpatch` command and do not assume `./bin/formpatch` exists.
- If Formpatch is not available in the target environment, fall back to the appropriate general editing tool instead of inventing a repo-local Formpatch path.

## Handle Model

Each top-level object has:

- `oid`: stable file-local object identity encoded as a 6-character base62 token
- `rev`: short hash of the current full object text
- `file_rev`: short hash of the whole file

Default behavior:

- use `oid` to target an object
- append `@rev` when you want object-level optimistic locking
- pass `--file-rev` only when you need strict whole-file optimistic locking

This means one `list` can often support multiple follow-up edits without re-listing, as long as the target object itself has not changed.

## Workflow

1. Run `formpatch list --file <path>` and inspect the returned JSON.
2. Pick the target object's `oid`; keep `rev` when you want a write guard.
3. If the preview is not enough to safely rewrite the full form, run `get` for the target objects.
4. Draft full replacement forms.
5. Prefer `--dry-run --diff` before mutating.
6. Run `insert` or `replace`.
7. Read the returned minimal mutation delta JSON and keep using the new `file_rev`, `touched`, `before`, and `after` handles instead of re-listing immediately.
8. Re-run `list` only when you need fresh discovery context, more full objects, or the mutation result does not give enough anchors.

## Query

List top-level objects:

```bash
formpatch list --file src/foo/core.clj
```

Interpret the response as:

- `file_rev`: current file version
- `oid`: stable file-local 6-character base62 identity
- `rev`: optimistic lock for that object
- `index`: current position in the file
- `text`: truncated preview text
- `text_truncated`: whether the preview was truncated
- `head` / `name`: best-effort metadata for display and selection

Fetch one or more full objects:

```bash
formpatch get \
  --file src/foo/core.clj \
  --objects <oid>,<oid>@<rev>
```

## Create

Insert new top-level forms with raw `stdin`.

Insert after an anchor object:

```bash
formpatch insert \
  --file src/foo/core.clj \
  --after <oid>@<rev> \
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

Insert at the head of the file (no anchor needed):

```bash
formpatch insert \
  --file src/foo/core.clj \
  --head <<'EOF'
(ns foo.core)
EOF
```

Use `--tail` to append at the end of the file. Both `--head` and `--tail` work on empty files.

Successful `insert` returns minimal delta JSON including:

- `file_rev`
- `touched`
- `deleted`
- `before`
- `after`
- optional `diff`

## Update

Replace one object by rewriting the full form:

```bash
formpatch replace \
  --file src/foo/core.clj \
  --targets <oid>@<rev> \
  --dry-run \
  --diff <<'EOF'
(defn updated
  [x]
  (inc x))
EOF
```

Replace one object with multiple objects by passing multiple top-level forms on `stdin`.

Replace multiple objects only when the target objects are contiguous in the current file.

If you need strict whole-file CAS, add `--file-rev <file_rev>`.

## Delete

Delete with `replace --empty`:

```bash
formpatch replace \
  --file src/foo/core.clj \
  --targets <oid>@<rev> \
  --empty
```

Delete multiple objects only when the target objects are contiguous.

Deletion returns the removed `oid`s in `deleted`.

## Guardrails

- Prefer `oid@rev` for writes. Bare `oid` is convenient, but it will not protect you from overwriting a changed target object.
- Treat `oid` as file-local. Do not assume it is globally unique across files or repositories.
- Use `--file-rev` only when you truly need strict whole-file locking; otherwise it reduces handle longevity.
- Start with this skill only when Formpatch is the intended tool or is already available. Escalate to lower-level `rewrite-clj` work or another general editor when whole-object replacement is not sufficient or Formpatch is unavailable.
- Keep edits at top-level object granularity. If only part of a function changes, still replace the full function.
- Use `--dry-run --diff` before writing unless the change is trivial.
- Pass raw top-level forms through `stdin`. Do not wrap replacement code in JSON strings.
- Expect JSON on `stdout` for success and machine-readable JSON on `stderr` for failure.
- After a mutation, prefer the returned `touched` / `before` / `after` handles over an immediate extra `list`.
- When working inside this repository, prefer the repo-local entrypoint `./bin/formpatch`. Outside repo development, prefer the installed `formpatch` command and do not probe `./bin/formpatch` first.
