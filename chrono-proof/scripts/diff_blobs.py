#!/usr/bin/env python3
"""Artifact differential + reader-execution proof for the Chrono target.

Loads BOTH artifacts with chronohive's Python reader (blob.py), unmodified:
  * the .cspec produced end-to-end by the built lfc (target Chrono -> Java
    extraction -> Rust lowering via the harness `lower-model`), and
  * the reference artifact produced by the real Rust chronoc binary from the
    Python-target fixture (REQ-112 flow: --skip-lfc --capacity storage_bw=1000).

Both artifacts carry the settled name: Constraint Specification,
`.cspec`, magic `CSP1`. Readers accept CSP1 only — there is no
dual-magic compatibility shim (the provisional `CSF1` magic this branch
once emitted was removed by the coordinated CSP1 rename pass).

Asserts:
  * capacities, operations (id, effector, on_refuse, demands, deps),
    schedule, and bindings are IDENTICAL between the two artifacts;
  * meta differs ONLY in lf_sha256 (source text differs), lfc_version
    ("lfc 0.13.0" vs "skipped"), and lf_target ("Chrono" vs "Python");
    chronoc_version, target_steps, and step_ns are identical;
  * both artifacts EXECUTE under the reader's Runtime with stub effectors:
    6 steps completed, train_step admitted 6x, admit_checkpoint 2x (steps 3
    and 6), prefetch 6x, zero refusals/drops; run lf_target passes through.

Usage: diff_blobs.py <lfc.cspec> <reference.cspec> <wt-target-chronohive-src-dir>
"""

import importlib.util
import sys
from pathlib import Path


def load_reader(src_dir: Path, tag: str):
    """Import blob.py from the chronohive worktree, unmodified (its magic
    check expects the settled CSP1, which both artifacts now carry)."""
    tmp = Path(f"/tmp/chrono_proof_reader_{tag}")
    pkg = tmp / "chronohive"
    pkg.mkdir(parents=True, exist_ok=True)
    (pkg / "__init__.py").write_text("")
    (pkg / "blob.py").write_text((src_dir / "chronohive" / "blob.py").read_text())
    # blob.py imports the kernel from .runtime in the same package.
    (pkg / "runtime.py").write_text((src_dir / "chronohive" / "runtime.py").read_text())
    sys.path.insert(0, str(tmp))
    for mod in ("chronohive.blob", "chronohive.runtime", "chronohive"):
        sys.modules.pop(mod, None)
    try:
        return importlib.import_module("chronohive.blob")
    finally:
        sys.path.pop(0)


def fail(msg):
    print(f"ARTIFACT PROOF: FAIL: {msg}")
    sys.exit(1)


def ops_view(blob):
    return [
        (t.id, t.effector, t.on_refuse, t.demands, t.deps) for t in blob.operations
    ]


def main():
    cspec_path, ref_path, src_dir = sys.argv[1], sys.argv[2], Path(sys.argv[3])

    cspec_magic = Path(cspec_path).read_bytes()[:4]
    ref_magic = Path(ref_path).read_bytes()[:4]
    if cspec_magic != b"CSP1":
        fail(f".cspec magic is {cspec_magic!r}, expected b'CSP1' (Constraint Specification)")
    if ref_magic != b"CSP1":
        fail(f"reference magic is {ref_magic!r}, expected b'CSP1' (settled Rust writer)")

    cspec_mod = load_reader(src_dir, tag="cspec")
    ref_mod = load_reader(src_dir, tag="ref")
    cspec = cspec_mod.load_blob(cspec_path)
    ref = ref_mod.load_blob(ref_path)

    if cspec.capacities != ref.capacities:
        fail(f"capacities differ: cspec={cspec.capacities} ref={ref.capacities}")
    if ops_view(cspec) != ops_view(ref):
        fail(f"operations differ:\n  cspec={ops_view(cspec)}\n  ref={ops_view(ref)}")
    if [(s.step_no, s.ops) for s in cspec.schedule] != [(s.step_no, s.ops) for s in ref.schedule]:
        fail("schedules differ")
    if cspec.bindings != ref.bindings:
        fail(f"bindings differ: cspec={cspec.bindings} ref={ref.bindings}")

    m, r = cspec.meta, ref.meta
    if m.lf_target != "Chrono":
        fail(f"cspec meta.lf_target = {m.lf_target!r}, expected 'Chrono'")
    if r.lf_target != "Python":
        fail(f"reference meta.lf_target = {r.lf_target!r}, expected 'Python'")
    if m.lf_sha256 == r.lf_sha256:
        fail("lf_sha256 must differ (the two fixture sources differ in the target line)")
    if m.lfc_version == r.lfc_version:
        fail("lfc_version must differ ('lfc 0.13.0' from the built compiler vs 'skipped')")
    for field in ("chronoc_version", "target_steps", "step_ns"):
        if getattr(m, field) != getattr(r, field):
            fail(f"meta.{field} differs: cspec={getattr(m, field)} ref={getattr(r, field)}")

    # --- Execute both under the reader's Runtime with stub effectors. ---
    for mod, blob, label in ((cspec_mod, cspec, "cspec"), (ref_mod, ref, "ref")):
        calls = []

        def stub(name):
            def run(ctx):
                calls.append((ctx.template_id, ctx.step))
            run.__name__ = name
            return run

        effectors = {name: stub(name) for name in ("train_step", "checkpoint_write", "prefetch_read")}
        report = mod.execute(blob, effectors)
        if report.lf_target != blob.meta.lf_target:
            fail(f"{label}: RunReport.lf_target {report.lf_target!r} != blob meta")
        if report.steps_completed != 6 or report.target_steps != 6:
            fail(f"{label}: steps {report.steps_completed}/{report.target_steps}, expected 6/6")
        admitted = {tid: rec.admitted for tid, rec in report.ops.items()}
        expected = {"t.train_step": 6, "c.admit_checkpoint": 2, "p.prefetch": 6}
        if admitted != expected:
            fail(f"{label}: admitted {admitted}, expected {expected}")
        if report.refused_total != 0 or report.dropped_total != 0:
            fail(f"{label}: refused={report.refused_total} dropped={report.dropped_total}, expected 0/0")

    print("ARTIFACT PROOF: PASS")
    print(f"  capacities : {list(cspec.capacities)}")
    for t in cspec.operations:
        print(f"  op         : {t.id} effector={t.effector} on_refuse={t.on_refuse} "
              f"demands={list(t.demands)} deps={list(t.deps)}")
    print(f"  schedule   : {[(s.step_no, list(s.ops)) for s in cspec.schedule]}")
    print(f"  bindings   : {list(cspec.bindings)}")
    print(f"  cspec meta : lf_target={m.lf_target!r} lfc_version={m.lfc_version!r} "
          f"chronoc_version={m.chronoc_version!r} target_steps={m.target_steps} step_ns={m.step_ns}")
    print(f"  ref meta   : lf_target={r.lf_target!r} lfc_version={ref.meta.lfc_version!r} "
          f"chronoc_version={ref.meta.chronoc_version!r} target_steps={ref.meta.target_steps}")
    print("  execution  : both artifacts ran 6/6 steps under the Python reader's "
          "Runtime; admissions t.train_step=6, c.admit_checkpoint=2, p.prefetch=6; "
          "0 refusals, 0 drops.")
    print("  magic      : both CSP1 (settled name, .cspec); parsed content identical.")


if __name__ == "__main__":
    main()
