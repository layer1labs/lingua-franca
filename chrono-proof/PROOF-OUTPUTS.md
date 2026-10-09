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

## CI verification (GitHub Actions, 2026-10-09)

The sandbox blocker above was routed around with CI: workflow
`.github/workflows/chrono-proof.yml` on this branch (push to
`chrono-target` + `workflow_dispatch`), ubuntu-latest, Temurin JDK 21.
The proof job checks out layer1labs/chronohive @ `feat/blob-lf-target`
(the worktree branch supplies both the chronoc sources the harness
compiles and the Python reader; cross-repo access via a repo secret —
org policy disables deploy keys on that repository), assembles lfc
with the real Gradle build (`./gradlew assemble` → installDist), then
runs `chrono-proof/scripts/proof.sh` unmodified. A second job runs
`./gradlew spotlessCheck`.

Green run: https://github.com/layer1labs/lingua-franca/actions/runs/37866022756
(both jobs success). Step results in the proof job:

- harness build (real chronoc sources) and real chronoc build: OK
- built lfc compiles `chrono-proof/fixtures/Top.lf` (`target Chrono`,
  capacities `storage_bw=1000` from the target property):
  `Top.csf` written via the Rust backend (3 ops, 6 steps, 568 bytes)
- reference artifact from the Python fixture via the real chronoc:
  3 ops, 6 steps, 565 bytes
- MODEL DIFFERENTIAL: PASS — 4 reactors (CheckpointIO, PrefetchIO,
  Top, Trainer) structurally identical between the Java AST
  extraction and the Rust frontend
- ARTIFACT PROOF: PASS — capacities/ops/schedule/bindings identical;
  both artifacts executed under the Python reader's Runtime: 6/6
  steps, admissions t.train_step=6, c.admit_checkpoint=2,
  p.prefetch=6, 0 refusals, 0 drops; meta differs only in
  lf_sha256 / lfc_version / lf_target, as documented

What the real Gradle build found (all fixed on this branch; the
sandbox javac parse check could not see any of these):

- `LFGenerator`'s Chrono case constructed `FileConfig` directly,
  but `FileConfig` is abstract — a minimal `ChronoFileConfig` was
  added (mirrors the Python target's `PyFileConfig`), and
  ChronoGenerator used an unconditional pattern in `instanceof`,
  which does not compile at this project's `-source 17` level.
- Exhaustive switches elsewhere broke: `DockerGenerator` and
  `FedLauncherGenerator` needed Chrono cases (both throw, matching
  the C++/Rust treatment — Docker and federation are unsupported).
- `ASTUtils.createMainReactorInstance` unconditionally writes the
  `compile-definitions` target property, so Target's Chrono case now
  registers `CompileDefinitionsProperty` (generator ignores it).
- Effects discrimination: `VarRef.getTransition()` is never null
  (the `ModeTransition` enum's first literal is the EMF default);
  mode-transition effects are now detected via
  `getVariable() instanceof Mode`, as upstream does.
- `Time` values: `forever`/`never` are separate grammar features
  (`getForever()`/`getNever()`), and the unit is a raw string
  converted with `TimeUnit.fromName` (subset units μs/ms/s/min only).
- Reaction bodies: `Code.getBody()` space-joins the datatype rule's
  tokens, destroying the line structure the Rust body parser needs;
  the body is now taken from the parse node's verbatim text, sliced
  between the `{=` and `=}` delimiters.
- The fixture's main reactor is named `Top`, and LF requires the
  file name to match — the fixture is `fixtures/Top.lf`.
- The rename landed on the chronohive branch mid-flight: the
  reference artifact and the Python reader now use the settled
  `CSP1` magic. `diff_blobs.py` tracks that on the reference side;
  this branch's artifact keeps the provisional `CSF1` until the
  coordinated rename pass sweeps it.

## Rename executed — CSP1 / `.cspec` (2026-10-08 night)

The owner picked the finalist recorded above: **`.cspec` / `CSP1`**
(Constraint Specification Format). The coordinated rename pass has
now swept this branch: the Java generator emits `.cspec` /
`.cspec-ir.json`, the proof harness no longer rewrites the Rust
writer's magic (the writer emits `CSP1` directly, CRC unchanged),
`diff_blobs.py` loads the Python reader unmodified for both
artifacts (readers accept `CSP1` only — no dual-magic shim), and
`proof.sh` / this kit's docs use the settled names throughout.

The sections above are the historical record of runs made while
`CSF1` was provisional; their quoted outputs are unchanged, exactly
as produced at the time.
