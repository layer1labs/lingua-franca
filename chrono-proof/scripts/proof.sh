#!/usr/bin/env bash
# End-to-end proof for the thin Chrono target (see ../README.md).
#
# Pipeline under test:
#   fixtures/three_intrinsics_chrono.lf  (target Chrono)
#     -> built lfc (this repo, branch chrono-target)
#     -> ChronoGenerator: real-AST extraction -> canonical model JSON
#     -> Rust lowering via `chronoc lower-model` (proof harness binary
#        wrapping the real chronoc sources) -> .csf
#   fixtures/three_intrinsics_python.lf  (target Python, the golden fixture)
#     -> real Rust chronoc binary (REQ-112 flow) -> reference .chb
#     -> harness `dump-model` -> Rust-frontend canonical model
#
# Proofs: (a) model-equality differential (diff_models.py),
#         (b) artifact differential + reader execution (diff_blobs.py).
#
# Prerequisites: JDK 21 (JAVA_HOME), cargo, python3, and the chronohive
# toolchain worktree at ~/workspace/chronohive-wt-target (chronoc sources +
# Python reader). The lfc distribution must be built first:
#   ./gradlew assemble:cli:lfc:assemble
set -euo pipefail

PROOF_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$PROOF_DIR/.." && pwd)"
WORK="$PROOF_DIR/work"
WT="${CHRONOHIVE_WT:-$HOME/workspace/chronohive-wt-target}"
LFC="$REPO_ROOT/build/install/lf-cli/bin/lfc"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21-full}"
export PATH="$HOME/.cargo/bin:$PATH"
export PATH="$JAVA_HOME/bin:$PATH"

[ -x "$LFC" ] || { echo "lfc not built: run ./gradlew assemble:cli:lfc:assemble first" >&2; exit 1; }
mkdir -p "$WORK"

echo "== 1. Build the lower-model proof harness (real chronoc sources) =="
(cd "$PROOF_DIR/harness" && cargo build --release --quiet)
HARNESS="$PROOF_DIR/harness/target/release/chrono-lower-model-proof"

echo "== 2. Build the real Rust chronoc (reference, from the toolchain worktree) =="
(cd "$WT/toolchain/chronoc" && cargo build --release --quiet)
CHRONOC_REF="$WT/toolchain/chronoc/target/release/chronoc"

echo "== 3. Compile the Chrono fixture with the built lfc =="
rm -rf "$WORK/lfc-out" && mkdir -p "$WORK/lfc-out"
CHRONOC="$HARNESS" "$LFC" -o "$WORK/lfc-out" "$PROOF_DIR/fixtures/three_intrinsics_chrono.lf"
JAVA_MODEL="$WORK/lfc-out/src-gen/ThreeIntrinsicsChrono/three_intrinsics_chrono.chrono-model.json"
CSF="$WORK/lfc-out/src-gen/ThreeIntrinsicsChrono/three_intrinsics_chrono.csf"
[ -f "$JAVA_MODEL" ] || JAVA_MODEL="$(find "$WORK/lfc-out" -name '*.chrono-model.json' | head -1)"
[ -f "$CSF" ] || CSF="$(find "$WORK/lfc-out" -name '*.csf' | head -1)"
echo "   model: $JAVA_MODEL"
echo "   csf  : $CSF"

echo "== 4. Reference artifact + Rust-frontend model from the Python fixture =="
"$CHRONOC_REF" compile "$PROOF_DIR/fixtures/three_intrinsics_python.lf" \
    --skip-lfc --capacity storage_bw=1000 -o "$WORK/reference.chb"
"$HARNESS" dump-model "$PROOF_DIR/fixtures/three_intrinsics_python.lf" -o "$WORK/rust-model.json"

echo "== 5. Model-equality differential =="
python3 "$PROOF_DIR/scripts/diff_models.py" "$JAVA_MODEL" "$WORK/rust-model.json"

echo "== 6. Artifact differential + reader execution =="
python3 "$PROOF_DIR/scripts/diff_blobs.py" "$CSF" "$WORK/reference.chb" "$WT/src"

echo "PROOF COMPLETE"
