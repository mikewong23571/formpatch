# code-editor

Top-level object editor for Clojure source files.

The tool is designed for AI agents. It exposes each file as an ordered list of top-level objects and supports two mutations:

- `insert`: insert zero or more top-level objects before or after an existing object
- `replace`: replace one or more contiguous top-level objects with zero or more new objects

The runtime path is `babashka`, so normal CLI usage does not pay JVM startup cost. `rewrite-clj` is used for parsing and validation.

## Install As A bb Tool

The project is packaged as a `bbin`-installable Babashka tool via [`bb.edn`](./bb.edn).

Install from a local checkout:

```bash
bbin install . --as clj-objects
```

After publishing the repository, install from git:

```bash
bbin install io.github.<owner>/code-editor --as clj-objects
```

The tool entrypoint is declared in `:bbin/bin` and resolves to:

```bash
bb -m mike.code-editor.cli/-main
```

## Commands

Show usage:

```bash
./bin/clj-objects --help
```

List top-level objects in a file:

```bash
./bin/clj-objects list --file src/mike/code_editor.clj
```

Insert objects after an anchor:

```bash
./bin/clj-objects insert \
  --file src/mike/code_editor.clj \
  --snapshot abc12345 \
  --after 1:19ab7def <<'EOF'
(defn helper-a
  []
  :a)

(defn helper-b
  []
  :b)
EOF
```

Replace one object with multiple objects:

```bash
./bin/clj-objects replace \
  --file src/mike/code_editor.clj \
  --snapshot abc12345 \
  --targets 1:19ab7def <<'EOF'
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
./bin/clj-objects replace \
  --file src/mike/code_editor.clj \
  --snapshot abc12345 \
  --targets 3:52de7abc \
  --empty
```

Preview a mutation without writing:

```bash
./bin/clj-objects replace \
  --file src/mike/code_editor.clj \
  --snapshot abc12345 \
  --targets 1:19ab7def \
  --dry-run \
  --diff <<'EOF'
(defn preview []
  :ok)
EOF
```

## Output

All successful commands emit JSON on `stdout`.

Object handles are short and snapshot-local:

- `snapshot`: short file hash
- `id`: top-level object index in that snapshot
- `hash`: short hash of the object text

Every successful mutation returns a fresh snapshot and a fresh object list.

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
bb clj-objects --help
```

Run the tool entrypoint exactly as `bbin` will invoke it:

```bash
bb -m mike.code-editor.cli/-main --help
```

## Design Notes

The design document is in [rewrite-clj-object-editing-design.md](./rewrite-clj-object-editing-design.md).
