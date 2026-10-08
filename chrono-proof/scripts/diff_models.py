#!/usr/bin/env python3
"""Model-equality differential for the thin Chrono target.

Compares the canonical model the Java Chrono target extracted from the real
LF AST (three_intrinsics_chrono.lf, `target Chrono`) against the canonical
model produced from the Rust frontend's AST for the same program
(three_intrinsics_python.lf, `target Python`, via the harness `dump-model`).

Contract under test (language policy, 2026-10-08): the Java target is a thin
extraction layer; Rust owns all lowering semantics. The extracted model must
be complete enough that the Rust lowering needs nothing else — proven here by
field-by-field equality of the program structure, and in diff_blobs.py by the
identical artifacts both models lower to.

Fields compared exactly: reactors (name, is_main, params, states, timers,
inputs, outputs, actions, reactions [triggers, effects, line], instances,
connections) and reaction bodies (compared after .strip(); raw bodies must
also be equal modulo leading/trailing whitespace only).

Fields intentionally NOT compared here (they legitimately differ):
  target       "Chrono" (Java extraction) vs "Python" (Rust frontend input)
  source_text  the two fixture files differ in the target line
  lfc_version  "lfc 0.13.0" (built compiler) vs "dump-model" (harness)
  config.capacities  declared via the Chrono target property on the Java side;
               asserted here to be exactly [["storage_bw", 1000]] and then
               injected into the Rust model for the artifact differential.

Usage: diff_models.py <java-model.json> <rust-model.json>
"""

import json
import sys


def fail(msg):
    print(f"MODEL DIFFERENTIAL: FAIL: {msg}")
    sys.exit(1)


def main():
    java = json.load(open(sys.argv[1]))
    rust = json.load(open(sys.argv[2]))

    assert java["format"] == rust["format"] == "chrono-model"
    assert java["version"] == rust["version"] == 1
    if java["target"] != "Chrono":
        fail(f"Java-extracted model target is {java['target']!r}, expected 'Chrono'")
    if rust["target"] != "Python":
        fail(f"Rust-frontend model target is {rust['target']!r}, expected 'Python'")
    if java["chronoc_version"] != "0.1.0-alpha.1":
        fail(f"chronoc_version {java['chronoc_version']!r} != 0.1.0-alpha.1")
    if not java["lfc_version"].startswith("lfc "):
        fail(f"lfc_version {java['lfc_version']!r} does not come from the built compiler")
    if java["binding_profile"] != "hive":
        fail(f"binding_profile {java['binding_profile']!r} != 'hive' (default)")
    if java["config"]["capacities"] != [["storage_bw", 1000]]:
        fail(f"capacities from target property = {java['config']['capacities']}, "
             "expected [[\"storage_bw\", 1000]]")
    if java["config"]["mb_bw"] != 9 or java["config"]["depth_bw"] != 10:
        fail("REQ-103 cost-model defaults mb_bw=9 / depth_bw=10 not in model config")

    jr = {r["name"]: r for r in java["reactors"]}
    rr = {r["name"]: r for r in rust["reactors"]}
    if set(jr) != set(rr):
        fail(f"reactor sets differ: java={sorted(jr)} rust={sorted(rr)}")

    for name in sorted(jr):
        a, b = jr[name], rr[name]
        for field in ("is_main", "params", "states", "timers", "inputs",
                      "outputs", "actions", "instances", "connections"):
            if a[field] != b[field]:
                fail(f"reactor {name}: field {field} differs:\n  java={a[field]}\n  rust={b[field]}")
        if len(a["reactions"]) != len(b["reactions"]):
            fail(f"reactor {name}: reaction count differs")
        for ra, rb in zip(a["reactions"], b["reactions"]):
            for field in ("triggers", "effects", "line"):
                if ra[field] != rb[field]:
                    fail(f"reactor {name}: reaction {field} differs: "
                         f"java={ra[field]} rust={rb[field]}")
            if ra["body"].strip() != rb["body"].strip():
                fail(f"reactor {name}: reaction body differs:\n"
                     f"  java={ra['body']!r}\n  rust={rb['body']!r}")
            if ra["body"] != rb["body"]:
                print(f"  note: reactor {name}: body text differs only in "
                      "leading/trailing whitespace (stripped forms equal)")

    print(f"MODEL DIFFERENTIAL: PASS — {len(jr)} reactors "
          f"({', '.join(sorted(jr))}) structurally identical between the "
          "Java AST extraction and the Rust frontend; target/lfc_version/"
          "source_text/capacities differ only as documented.")


if __name__ == "__main__":
    main()
