# Proof outputs — recorded 2026-10-08

Environment: this sandbox VM (2 CPUs, ~8 GB RAM), JDK Temurin 21.0.12.1
(full JDK at `~/workspace/tools/jdk-21-full`), Rust via `~/.cargo/bin`.

## ⚠ What is NOT proven here: the lfc invocation itself

`./gradlew assemble:cli:lfc:assemble` cannot complete in this sandbox:
Gradle 8.8's daemon IPC fails deterministically. The daemon starts and
listens on 127.0.0.1; the client TCP-connects and dispatches the Build
command; the daemon then closes the connection seconds later without
logging or executing anything, and the client fails with
`NoUsableDaemonFoundException: A new daemon was started but could not be
connected to`. Reproduced across: JRE and full JDK, Gradle home on tmpfs
and on the workspace overlay, fresh daemon registries, single-client
clean rooms, detached and foreground launches, stdin held open and
closed. Loopback TCP itself was verified healthy (Python servers,
including detached and 15 s slow-reply cases). Full logs:
`gradle-daemon-blocker.log`, `gradle-daemon-blocker-daemon.log`.

Consequence: the Java target code in this branch is verified at source
level (every LF API it calls was checked against the v0.13.0 sources;
`javac` parses all new/modified files with zero syntax errors) but has
NOT been compiled or run under lfc in this environment. The proofs below
cover everything downstream of the Java extraction, using a stand-in
model whose program structure comes from the real Rust frontend and
whose config/target/source_text are exactly what the Java generator
emits for the Chrono fixture. In a normal build environment,
`scripts/proof.sh` runs the true end-to-end pipeline unchanged.

## Proof 1 — canonical model sufficiency (byte identity)

The harness `lower-model`, fed the canonical model of the golden fixture
with the same inputs the real chronoc used (lfc_version "skipped",
capacity storage_bw=1000, target "Python"), produces an artifact
**byte-identical** to the real Rust chronoc binary's output, except the
4 magic bytes (CSF1 vs CHB1 — differing body bytes: [1, 2]) and the CRC-32
trailer that covers them:

```
bytecmp.csf magic: b'CSF1' | reference.chb magic: b'CHB1'
lengths: 565 565
body byte diffs (excluding trailer): [1, 2]
crc ok (csf): True | crc ok (ref): True
```

This is the production property the thin-target design relies on: the
canonical model is a complete input for the single Rust lowering.

## Proof 2 — model differential (script logic validated)

`diff_models.py` run with the stand-in model as the Java side against the
Rust-frontend model (in the real pipeline the Java side is the file lfc
writes; the script's assertions on target/profile/capacities/
chronoc_version were all exercised):

```
MODEL DIFFERENTIAL: PASS — 4 reactors (CheckpointIO, PrefetchIO, Top,
Trainer) structurally identical between the Java AST extraction and the
Rust frontend; target/lfc_version/source_text/capacities differ only as
documented.
```

## Proof 3 — artifact differential + reader execution (FULL PASS)

`diff_blobs.py work/standin.csf work/reference.chb` — the stand-in .csf
(lowered from the canonical model with target "Chrono", lfc_version
"lfc 0.13.0", Chrono-fixture source text) vs the real chronoc's artifact,
both loaded and executed by chronohive's Python reader (blob.py; the
.csf through a copy with only the magic constant renamed to CSF1, the
coordinated rename):

```
ARTIFACT PROOF: PASS
  capacities : [('storage_bw', 1000)]
  op         : t.train_step effector=train_step on_refuse=must_admit demands=[] deps=[]
  op         : c.admit_checkpoint effector=checkpoint_write on_refuse=retry demands=[('storage_bw', 72)] deps=[0]
  op         : p.prefetch effector=prefetch_read on_refuse=drop demands=[('storage_bw', 40)] deps=[0]
  schedule   : [(1, [0, 2]), (2, [0, 2]), (3, [0, 1, 2]), (4, [0, 2]), (5, [0, 2]), (6, [0, 1, 2])]
  bindings   : [('t.Trainer::reaction(tick)::train_step', 0), ('c.CheckpointIO::reaction(ckpt)::admit_checkpoint', 1), ('p.PrefetchIO::reaction(tick)::prefetch', 2)]
  csf meta   : lf_target='Chrono' lfc_version='lfc 0.13.0' chronoc_version='0.1.0-alpha.1' target_steps=6 step_ns=1000000
  ref meta   : lf_target='Python' lfc_version='skipped' chronoc_version='0.1.0-alpha.1' target_steps=6
  execution  : both artifacts ran 6/6 steps under the Python reader's Runtime; admissions t.train_step=6, c.admit_checkpoint=2, p.prefetch=6; 0 refusals, 0 drops.
  magic      : csf=CSF1 vs ref=CHB1 — the intentional, settled rename (Constraint Specification Format); parsed content identical.
```

Note (2026-10-08, later): the owner reopened the format name after a
collision report (.csf is used by Adobe Color Settings, GeoMedia,
Cal3D); finalists are `.cspec`/CSP1, `.chrono`/CHR1, `.constr`/CNS1.
CSF1 is used provisionally throughout this branch until the pick is
broadcast; renaming later touches only the magic constant, the file
extension, and the IR format string.
