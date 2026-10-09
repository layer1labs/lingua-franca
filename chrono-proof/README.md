# Chrono target — proof kit

End-to-end proof for the `Chrono` Lingua Franca target on branch
`chrono-target` (lf-lang/lingua-franca v0.13.0 + the Chrono target).

## Design (language policy, 2026-10-08)

Everything Layer1Labs owns is Rust. The one exception is inside `lfc`
itself, because LF targets are Java — so the Java side is **thin**:

- `core/src/main/java/org/lflang/target/Target.java` — the `Chrono` enum
  entry (Python keyword set; conservative capabilities: single-threaded,
  no federation), plus target-property registration
  (`core/src/main/java/org/lflang/target/property/BindingProfileProperty.java`,
  `ChronoCapacitiesProperty.java`).
- `core/src/main/java/org/lflang/generator/LFGenerator.java` — dispatch to
  the generator and its `FileConfig`.
- `core/src/main/java/org/lflang/generator/chrono/ChronoGenerator.java` —
  extraction only: it walks the **real LF AST** (`org.lflang.lf`) and the
  reactor-instance graph (built as the compiler's structural gate),
  validates the ChronoHive LF subset v1 (spec 002, REQ-102) with precise
  errors, emits the **canonical model** (`chrono-model` JSON v1, schema in
  the class javadoc), and invokes the single Rust lowering implementation:

  ```
  chronoc lower-model <model.json> -o <name>.cspec --emit-ir <name>.cspec-ir.json
  ```

  (located via `-Dchrono.chronoc`, `$CHRONOC`, or `PATH`).

There is exactly **one** Chrono target. The artifact (Constraint
Specification Format, `.cspec`, magic `CSP1`) is engine-neutral:
`binding-profile` is a compile-time validation knob only and is recorded
nowhere in the artifact.

An earlier checkpoint on this branch implemented the lowering in Java; it
was replaced by this thin design per the language policy (the commit
remains in branch history as a reference, and the differential below is
what now guards the contract).

## The shared contract

1. The **byte-format spec**: chronohive spec 002 (REQ-103..106, REQ-113) —
   one format, v1, produced by the Rust writer (`blob.rs`), consumed by
   language-agnostic readers.
2. The **canonical model schema** (`chrono-model` v1): everything the Rust
   lowering needs, extracted from the real LF AST. Guarded by the
   model-equality differential.
3. The **differentials** in `scripts/`:
   - `diff_models.py` — Java-extracted model vs Rust-frontend model,
     field-by-field.
   - `diff_blobs.py` — the `.cspec` from the full lfc pipeline vs the
     reference artifact from the real Rust chronoc: identical capacities,
     operations, schedule, bindings, demands; meta differing only in
     `lf_sha256`, `lfc_version`, `lf_target`; plus execution of both under
     chronohive's Python reader with stub effectors.

## Running

```sh
# from the repo root, with JAVA_HOME pointing at a JDK 21:
./gradlew assemble:cli:lfc:assemble
chrono-proof/scripts/proof.sh
```

## Build-environment note (2026-10-08)

In the sandbox VM where this branch was developed, the Gradle build
cannot complete: Gradle 8.8's daemon IPC fails deterministically (the
daemon accepts the client's connection, then closes it seconds later
without executing; the client reports `NoUsableDaemonFoundException`).
Loopback TCP, filesystems, JDK/JRE, and launch modes were all exonerated
individually; see `PROOF-OUTPUTS.md` and the preserved logs
(`gradle-daemon-blocker*.log`). The Java sources are verified at source
level (all LF APIs checked against the v0.13.0 tree; `javac` parses them
with zero syntax errors), and every downstream link of the pipeline is
proven in `PROOF-OUTPUTS.md`. On a normal build machine the two commands
above run the full end-to-end proof unchanged.

`proof.sh` uses the proof harness in `harness/` — a small Rust binary that
includes chronoc's real frontend/lowering/blob sources by `#[path]` from
the chronohive toolchain worktree and exposes the `lower-model` /
`dump-model` CLI. It stands in for the production `chronoc lower-model`
subcommand (same schema, same CLI) that the toolchain repo grows
natively; the harness exists to prove the schema is sufficient for the
real Rust lowering with nothing else.
