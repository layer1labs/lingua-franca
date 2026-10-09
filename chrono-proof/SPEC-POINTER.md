# Governing spec — pointer

The Chrono target in this fork is governed by **spec 004 —
`specs/004-lf-chrono-target`** in the toolchain repository
(`layer1labs/chronohive-toolchain`), not by any spec in this tree.

Spec-kit is deliberately **not** initialized in this upstream fork
(it would pollute future upstream merges); spec 004 is the fork
work's coverage home. In brief, it records:

- the **thin-Java design**: the Java side registers the target,
  walks the real LF AST, validates the ChronoHive LF subset v1, and
  emits the canonical `chrono-model` v1 JSON — no lowering semantics
  in Java;
- the **lower-model contract** with toolchain spec 002: the single
  Rust lowering is invoked as
  `chronoc lower-model <model.json> -o <name>.cspec --emit-ir <name>.cspec-ir.json`,
  emitting the Constraint Specification format (`.cspec`, magic
  `CSP1`);
- the **gates**: the end-to-end proof in this directory
  (`scripts/proof.sh`, run by `.github/workflows/chrono-proof.yml`)
  and the model-equality / artifact differentials in `scripts/`
  (Java-extracted model vs Rust-frontend model; lfc artifact vs
  Rust chronoc oracle — identical except `lf_sha256`,
  `lfc_version`, `lf_target`).

See `README.md` in this directory for the proof-kit design and how
to run it.
