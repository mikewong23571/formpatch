# formpatch

Top-level object editor for Clojure source files.

The tool is designed for AI agents. It exposes each file as an ordered list of top-level objects and supports two mutations:

- `insert`: insert zero or more top-level objects before or after an existing object, or at the head/tail of the file
- `replace`: replace one or more contiguous top-level objects with zero or more new objects

The runtime path is `babashka`, so normal CLI usage does not pay JVM startup cost. `rewrite-clj` is used for parsing and validation.

## Handle Model

Each top-level object has:

- `oid`: stable file-local object identity encoded as a 6-character base62 token
- `rev`: short hash of the current full object text
- `file_rev`: short hash of the whole file

The default workflow is:

- use `oid` to address an object
- optionally add `@rev` to guard against overwriting a changed object
- optionally pass `--file-rev` for strict whole-file optimistic locking

This means one `list` can usually support multiple follow-up edits without re-listing, as long as the target object itself has not changed.

## Install As A bb Tool

The project is packaged as a `bbin`-installable Babashka tool via [`bb.edn`](./bb.edn).

Install from a local checkout:

```bash
bbin install . --as formpatch
```

After publishing the repository, install from git:

```bash
bbin install io.github.mikewong23571/formpatch --as formpatch
```

The tool entrypoint is declared in `:bbin/bin` and resolves to:

```bash
bb -m formpatch.cli/-main
```

## Commands

Show usage:

```bash
./bin/formpatch --help
```

List top-level objects in a file:

```bash
./bin/formpatch list --file src/formpatch.clj
```

Fetch one or more full objects by handle:

```bash
./bin/formpatch get \
  --file src/formpatch.clj \
  --objects 00000a,00000b@19ab7def
```

Insert objects after an anchor:

```bash
./bin/formpatch insert \
  --file src/formpatch.clj \
  --after 00000a@19ab7def <<'EOF'
(defn helper-a
  []
  :a)

(defn helper-b
  []
  :b)
EOF
```

Insert at the head of the file (no anchor needed):

```bash
./bin/formpatch insert \
  --file src/formpatch.clj \
  --head <<'EOF'
(ns foo.core)
EOF
```

Use `--tail` to append at the end of the file. Both work on empty files.

Replace one object with multiple objects:

```bash
./bin/formpatch replace \
  --file src/formpatch.clj \
  --targets 00000a@19ab7def <<'EOF'
(defn helper
  [x]
  (inc x))

(defn updated
  [x]
  (helper x))
EOF
```

Delete objects without `stdin`:

```bash
./bin/formpatch replace \
  --file src/formpatch.clj \
  --targets 00000a@19ab7def \
  --empty
```

Preview a mutation without writing:

```bash
./bin/formpatch replace \
  --file src/formpatch.clj \
  --targets 00000a@19ab7def \
  --dry-run \
  --diff <<'EOF'
(defn preview []
  :ok)
EOF
```

Enable strict whole-file locking when needed:

```bash
./bin/formpatch replace \
  --file src/formpatch.clj \
  --file-rev abc12345 \
  --targets 00000a@19ab7def \
  --empty
```

## Output

`list` emits JSON on `stdout`, but each object's `text` is truncated by default to reduce token usage.

`get` emits JSON on `stdout` with the full object text for one or more objects.

`insert` and `replace` emit a minimal mutation delta JSON on `stdout`. The response includes:

- `file_rev`: the new file version
- `touched`: the objects created or updated by the mutation
- `deleted`: removed `oid`s
- `before` / `after`: stable neighbor anchors around the changed span

Use `--diff` when you also want a unified diff string embedded in the same JSON response.

## Identity Store

`formpatch` persists object identities in a small EDN sidecar store. By default it lives under:

```text
~/.cache/formpatch/state
```

You can override it with:

- env var `FORMPATCH_STATE_DIR`
- JVM property `formpatch.state-dir`

## Formatting Rules

The wrapper applies a small deterministic formatting policy:

- top-level objects are joined with exactly two blank lines
- the file ends with exactly one trailing newline
- object-internal formatting is preserved exactly as provided

## Development

Run the JVM test suite:

```bash
clojure -T:build test
```

Run the Babashka task wrapper:

```bash
bb formpatch --help
```

Run the tool entrypoint exactly as `bbin` will invoke it:

```bash
bb -m formpatch.cli/-main --help
```

## Design Notes

The design document is in [rewrite-clj-object-editing-design.md](./rewrite-clj-object-editing-design.md).
