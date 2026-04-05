# Rewrite-clj Object Editing Design

## Goal

Define a minimal editing protocol for Clojure source files on top of `rewrite-clj`.

The protocol is designed for AI agents, not human editors. The main constraint is to keep the surface area small, stable, and composable while preserving source structure and keeping file formatting reasonable.

## Core Idea

Treat each file as an ordered list of top-level objects.

The editing API does not expose arbitrary tree surgery. Every edit is anchored to file-level top-level objects and operates by inserting or replacing one or more of those objects.

This keeps the model simple:

- query the file as top-level objects
- choose one or more target objects
- replace them with zero or more new objects
- or insert zero or more new objects before or after an anchor object

## Non-Goals

- No fine-grained object-internal editing API
- No persistent semantic id across file revisions
- No formatter built into the structural editor
- No requirement to classify top-level objects into a closed type hierarchy

## Why This Model

For AI-driven editing, top-level objects are the most useful stable unit:

- they are easy to enumerate with `rewrite-clj`
- their boundaries are usually clear
- most real edits can be expressed as whole-object replacement
- they are much less fragile than line/column or raw text offsets

This model also avoids exposing too much zipper detail to the caller.

## File Model

Each file is exposed as:

```edn
{:file "/abs/path/example.clj"
 :snapshot "a91c2e7f"
 :objects
 [{:id 0
   :hash "19ab"
   :text "(ns demo.core ...)"
   :readable? true
   :head 'ns
   :name 'demo.core}
  {:id 1
   :hash "52de"
   :text "(defn foo [x] ...)"
   :readable? true
   :head 'defn
   :name 'foo}]}
```

### Object Semantics

An object is a top-level node parsed from the file by `rewrite-clj`.

Rules:

- top-level objects are ordered
- pure whitespace is not exposed as an object
- comments immediately attached to a top-level form should be included in that object's `text`
- top-level forms that cannot safely round-trip through `sexpr` are still exposed
- objects do not need a strict type system; `head` and `name` are advisory metadata only

### Required Object Fields

- `:id`
  - short integer id within one file snapshot
- `:hash`
  - short content hash for optimistic validation
- `:text`
  - raw object source text
- `:readable?`
  - whether richer parsing metadata was available

### Optional Object Fields

- `:head`
  - first symbol when readable, such as `ns`, `defn`, `defmethod`
- `:name`
  - object name when readable
- `:meta`
  - reserved for future derived metadata

## Handle Model

Handles should be short and snapshot-local.

Do not use large opaque ids carrying path, full hashes, and structural paths. They cost more to serialize, more to compare, and do not help the caller.

Instead:

- file identity is carried by `:file`
- snapshot identity is carried by `:snapshot`
- object identity within that snapshot is carried by `:id`
- object validation is carried by `:hash`

This means the practical handle is:

```edn
{:snapshot "a91c2e7f"
 :id 4
 :hash "52de"}
```

### Handle Validity

- `:id` is valid only within its `:snapshot`
- any successful edit creates a new file snapshot
- after an edit, previous `:id` values must be considered stale
- callers are expected to re-query the file after each successful mutation

This trades persistent ids for simplicity and robustness.

## Operations

Only three operations are needed.

The examples in this section describe the logical protocol shape. A CLI may map the `new-objects` payload to raw `stdin` instead of passing object source inline.

### 1. `list-objects`

Reads a file and returns the file model.

```edn
{:op :list-objects
 :file "/abs/path/example.clj"}
```

Response:

```edn
{:file "/abs/path/example.clj"
 :snapshot "a91c2e7f"
 :objects [...]}
```

### 2. `insert-objects`

Insert one or more new top-level objects before or after an anchor object.

```edn
{:op :insert-objects
 :file "/abs/path/example.clj"
 :snapshot "a91c2e7f"
 :anchor-id 3
 :anchor-hash "91df"
 :position :after
 :new-objects
 ["(defn helper-a [] ...)"
  "(defn helper-b [] ...)"]}
```

Properties:

- supports adding multiple top-level objects in one call
- anchor is a single existing object
- insertions are ordered exactly as provided in `:new-objects`

### 3. `replace-objects`

Replace one or more contiguous top-level objects with zero or more new top-level objects.

```edn
{:op :replace-objects
 :file "/abs/path/example.clj"
 :snapshot "a91c2e7f"
 :targets
 [{:id 4 :hash "52de"}
  {:id 5 :hash "77ac"}]
 :new-objects
 ["(defn new-helper [] ...)"
  "(defn updated-foo [] ...)"]}
```

Properties:

- `1 -> 1` is normal whole-object replacement
- `1 -> N` supports splitting one object into many
- `N -> 1` supports merging objects
- `N -> 0` is deletion
- targets must be contiguous in the source file

## Why No Separate `remove`

Deletion is already expressed by:

```edn
{:op :replace-objects
 :targets [...]
 :new-objects []}
```

Keeping only `insert-objects` and `replace-objects` keeps the protocol more orthogonal.

## CLI Shape

The protocol will likely be exposed as a CLI for AI agents. The CLI should optimize for:

- minimal shell escaping
- cheap machine parsing
- low command count
- direct support for multi-line top-level forms

### Recommended Command Set

- `list`
- `insert`
- `replace`

### Input and Output Split

Use different channels for control data and source data.

- control data should be passed as CLI arguments
- source objects should be passed as raw `stdin`
- command results should be emitted as JSON on `stdout`

This avoids wrapping multi-line Clojure code inside JSON strings.

### Why Not JSON Payloads for Edits

Putting `new-objects` inside JSON is possible, but hostile to AI and shell usage because every newline and quote inside source text must be escaped.

This is the shape to avoid:

```json
{
  "new_objects": [
    "(defn foo\n  [x]\n  (+ x 1))"
  ]
}
```

The problem is not `stdin` itself. The problem is embedding source code inside JSON strings.

### Recommended Edit Input Model

For `insert` and `replace`:

- path, snapshot, and target selection go in flags
- replacement or inserted top-level forms are read from raw `stdin`

Example:

```bash
formpatch replace \
  --file /abs/path/core.clj \
  --snapshot a91c2e7f \
  --targets 4:52de <<'EOF'
(defn foo
  [x]
  (+ x 1))
EOF
```

Replacing one object with multiple top-level objects:

```bash
formpatch replace \
  --file /abs/path/core.clj \
  --snapshot a91c2e7f \
  --targets 4:52de <<'EOF'
(defn helper-a
  [x]
  (+ x 1))

(defn foo
  [x]
  (helper-a x))
EOF
```

In this model, `stdin` is parsed as zero or more top-level objects.

### Deletion Without Stdin

Deletion should not require an empty `stdin` body. Use an explicit flag.

Example:

```bash
formpatch replace \
  --file /abs/path/core.clj \
  --snapshot a91c2e7f \
  --targets 4:52de \
  --empty
```

### Query Output

`list` should return JSON on `stdout`.

Example:

```json
{
  "ok": true,
  "file": "/abs/path/core.clj",
  "snapshot": "a91c2e7f",
  "objects": [
    {
      "id": 0,
      "hash": "19ab",
      "text": "(ns demo.core ...)",
      "readable": true,
      "head": "ns",
      "name": "demo.core"
    },
    {
      "id": 1,
      "hash": "52de",
      "text": "(defn foo [x] ...)",
      "readable": true,
      "head": "defn",
      "name": "foo"
    }
  ]
}
```

### Mutation Output

`insert` and `replace` should also return JSON on `stdout`.

At minimum:

- `ok`
- `file`
- `snapshot`
- `changed`

Optionally:

- updated object list
- diff output

### Error Output

On failure:

- exit with non-zero status
- emit a machine-readable JSON error object

Example:

```json
{
  "ok": false,
  "error": "object_hash_mismatch",
  "details": {
    "id": 4,
    "expected": "52de",
    "actual": "8a10"
  }
}
```

### Why This CLI Model Fits AI Agents

- no JSON escaping for multi-line source
- no need to encode top-level forms as arrays of lines
- shell heredocs are easy for agents to emit
- `rewrite-clj` can parse raw top-level forms directly
- output remains machine-readable and easy to chain

## Parsing and Validation Rules

All provided new source must parse as zero or more valid top-level forms.

Validation should happen before writing:

- confirm snapshot matches current file
- confirm every target object's current hash matches expected hash
- confirm `:targets` are contiguous
- confirm the provided replacement or inserted source parses as top-level nodes

If validation fails, no write should occur.

## Formatting Strategy

`rewrite-clj` is a structure-preserving parser/editor, not a formatter.

It helps retain source shape, comments, and trivia, but it should not be responsible for producing final top-level whitespace layout.

### Required Formatting Rules

The wrapper layer should enforce a tiny, deterministic top-level formatting policy:

- top-level objects are joined with exactly two newline characters
- the file ends with exactly one trailing newline
- object-internal formatting is preserved exactly as provided in each object's `:text`

This is enough to prevent the most common structural-edit artifacts:

- inserted objects sticking to the previous form
- whole-object replacements collapsing surrounding file whitespace

### Optional Formatter Pass

Projects may run `cljfmt` or `zprint` after mutation, but that is outside the structural edit protocol.

The structural editor must remain usable without a formatter.

## Mapping to Rewrite-clj

`rewrite-clj` is a good fit for this design.

### What Rewrite-clj Gives Us

- parse a file into top-level nodes
- walk those nodes while preserving comments and whitespace
- serialize nodes back to source text
- replace, remove, or insert top-level nodes safely

### What the Wrapper Must Add

- file snapshot generation
- short object ids
- per-object content hashes
- contiguous-range checks
- top-level whitespace normalization
- parse-before-write validation

## Implementation Sketch

### `list-objects`

1. open file with `rewrite-clj`
2. collect top-level nodes in order
3. compute file snapshot from normalized file text
4. assign `:id` as zero-based index
5. compute short object hash from object text
6. derive optional `:head` and `:name` when safe

### `insert-objects`

1. re-read file and verify snapshot
2. locate anchor object by `:id`
3. verify anchor hash
4. parse raw `stdin` into zero or more top-level nodes
5. splice parsed nodes before or after anchor
6. write full file using normalized top-level spacing
7. return new snapshot and updated object list

### `replace-objects`

1. re-read file and verify snapshot
2. locate target objects by ids
3. verify hashes
4. verify target ids form a contiguous range
5. parse raw `stdin` into zero or more top-level nodes, unless `--empty` was provided
6. replace target range with parsed nodes
7. write full file using normalized top-level spacing
8. return new snapshot and updated object list

## Error Model

Errors should be explicit and cheap to interpret.

Suggested failure kinds:

- `:snapshot-mismatch`
- `:object-not-found`
- `:object-hash-mismatch`
- `:non-contiguous-targets`
- `:invalid-new-object`
- `:write-failed`

Example:

```edn
{:ok? false
 :error :object-hash-mismatch
 :details {:id 4
           :expected "52de"
           :actual "8a10"}}
```

## Recommended Agent Workflow

1. call `list-objects`
2. choose target objects from returned list
3. synthesize one or more whole replacement objects
4. call `insert-objects` or `replace-objects`
5. re-read with `list-objects`
6. optionally run formatter, lint, and tests

This favors whole-object replacement over fine-grained tree manipulation.

## Tradeoffs

### Advantages

- very small protocol surface
- easy for AI agents to reason about
- stable enough without long-lived ids
- naturally supports multi-object insert and replace
- well aligned with `rewrite-clj`

### Costs

- encourages whole-object rewrites, which can increase diff size
- callers must re-query after each successful mutation
- large top-level forms can still be awkward to regenerate
- no built-in semantic rename or refactor behavior

## Future Extensions

Possible later additions, not required for the initial design:

- batch transactions with multiple insert/replace ops
- richer derived metadata for display only
- optional formatter integration hooks
- optional symbol/reference analysis layered above this protocol

## Decision Summary

The initial design should be:

- expose every top-level object in file order
- use short snapshot-local object ids
- validate edits with snapshot plus object hash
- support multi-object insert and multi-object replace
- treat deletion as replacement with an empty object list
- normalize only top-level whitespace in the wrapper
- rely on `rewrite-clj` for structure, not formatting
