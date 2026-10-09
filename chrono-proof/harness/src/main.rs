//! Proof harness for the `chrono-model` contract between the lfc Chrono target
//! (thin Java extraction) and the Rust chronoc lowering (the single lowering
//! implementation).
//!
//! This binary is NOT the production chronoc. It exists to prove, end to end,
//! that the canonical model JSON the Java target emits is *sufficient* for the
//! real Rust lowering: it includes chronoc's actual frontend/lowering/blob
//! sources by `#[path]` from the chronohive toolchain worktree (read-only) and
//! exposes exactly the CLI the Java generator invokes:
//!
//!   chrono-lower-model-proof lower-model <model.json> -o <out.cspec> --emit-ir <ir.json>
//!   chrono-lower-model-proof dump-model  <source.lf> -o <model.json>
//!
//! `lower-model` converts the canonical model into chronoc's `Program` AST,
//! runs the real `lower::lower` + `blob::write_blob`. The artifact magic is
//! `CSP1` (Constraint Specification Format, `.cspec`) — the settled name,
//! emitted by the Rust writer unchanged; this harness performs no magic
//! rewrite (an earlier provisional `CSF1` rewrite was removed by the
//! coordinated CSP1 rename pass).
//!
//! `dump-model` runs the real Rust frontend (lexer+parser) on an `.lf` file
//! and serializes the resulting `Program` into the same canonical model JSON
//! shape the Java target emits, for the model-equality differential.
//!
//! Production form: the toolchain repo's chronoc grows this same
//! `lower-model` subcommand natively (same schema, same CLI), at which point
//! this harness is only a test fixture.

use std::process::ExitCode;

// The real chronoc sources, included unmodified (read-only) from the
// toolchain worktree. CHRONOC_SRC can point at another checkout of the same
// sources; the default is the worktree that carries the REQ-113 lf_target
// field and version 0.1.0-alpha.1.

#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/ast.rs"]
mod ast;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/blob.rs"]
mod blob;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/body.rs"]
mod body;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/ir.rs"]
mod ir;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/lexer.rs"]
mod lexer;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/lower.rs"]
mod lower;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/manifest.rs"]
mod manifest;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/parser.rs"]
mod parser;
#[path = "/home/hatch/workspace/chronohive-wt-target/toolchain/chronoc/src/sha256.rs"]
mod sha256;

use ast::{ArgVal, Member, Program, Reaction, Reactor, TimeVal};
use lower::{Config, LowerInput};
use manifest::EffectorManifest;
use serde_json::{json, Value};

fn fail(msg: &str) -> ExitCode {
    eprintln!("error: {msg}");
    ExitCode::from(1)
}

fn get<'a>(v: &'a Value, key: &str) -> &'a Value {
    v.get(key).unwrap_or_else(|| panic!("model missing key {key}"))
}

fn as_str(v: &Value) -> String {
    v.as_str().unwrap_or_else(|| panic!("expected string, got {v}")).to_string()
}

fn as_i64(v: &Value) -> i64 {
    v.as_i64().unwrap_or_else(|| panic!("expected int, got {v}"))
}

fn time_val(v: &Value) -> TimeVal {
    TimeVal {
        amount: as_i64(get(v, "amount")) as i64,
        unit: as_str(get(v, "unit")),
    }
}

/// Canonical model JSON -> chronoc Program AST (the inverse of dump-model).
fn model_to_program(model: &Value) -> Program {
    let mut reactors = Vec::new();
    for r in get(model, "reactors").as_array().unwrap() {
        let mut members: Vec<Member> = Vec::new();
        for s in get(r, "states").as_array().unwrap() {
            members.push(Member::State {
                name: as_str(get(s, "name")),
                init: as_i64(get(s, "init")),
            });
        }
        for t in get(r, "timers").as_array().unwrap() {
            members.push(Member::Timer {
                name: as_str(get(t, "name")),
                offset: time_val(get(t, "offset")),
                period: time_val(get(t, "period")),
            });
        }
        // Logical actions are accepted syntactically by chronoc's parser as
        // timers named __action_<name>; lowering rejects their use.
        for a in get(r, "actions").as_array().unwrap() {
            let zero = TimeVal { amount: 0, unit: String::new() };
            members.push(Member::Timer {
                name: format!("__action_{}", as_str(a)),
                offset: zero.clone(),
                period: zero,
            });
        }
        for p in get(r, "inputs").as_array().unwrap() {
            members.push(Member::Port { name: as_str(p), is_input: true });
        }
        for p in get(r, "outputs").as_array().unwrap() {
            members.push(Member::Port { name: as_str(p), is_input: false });
        }
        for rx in get(r, "reactions").as_array().unwrap() {
            let triggers = get(rx, "triggers").as_array().unwrap().iter().map(as_str).collect();
            let effects = get(rx, "effects").as_array().unwrap().iter().map(as_str).collect();
            members.push(Member::Reaction(Reaction {
                triggers,
                effects,
                body: as_str(get(rx, "body")),
                line: as_i64(get(rx, "line")) as usize,
            }));
        }
        for i in get(r, "instances").as_array().unwrap() {
            let mut args = Vec::new();
            for a in get(i, "args").as_array().unwrap() {
                let pair = a.as_array().unwrap();
                let val = &pair[1];
                let av = if let Some(n) = val.get("int") {
                    ArgVal::Int(as_i64(n))
                } else {
                    ArgVal::Ref(as_str(get(val, "ref")))
                };
                args.push((as_str(&pair[0]), av));
            }
            members.push(Member::Instance {
                var: as_str(get(i, "name")),
                reactor: as_str(get(i, "reactor")),
                args,
            });
        }
        for c in get(r, "connections").as_array().unwrap() {
            members.push(Member::Connection {
                src_inst: as_str(get(c, "src_inst")),
                src_port: as_str(get(c, "src_port")),
                dst_inst: as_str(get(c, "dst_inst")),
                dst_port: as_str(get(c, "dst_port")),
            });
        }
        let mut params = Vec::new();
        for p in get(r, "params").as_array().unwrap() {
            params.push(ast::Param {
                name: as_str(get(p, "name")),
                default: as_i64(get(p, "default")),
            });
        }
        reactors.push(Reactor {
            name: as_str(get(r, "name")),
            is_main: get(r, "is_main").as_bool().unwrap(),
            params,
            members,
            line: 1,
        });
    }
    Program {
        target: as_str(get(model, "target")),
        reactors,
    }
}

/// chronoc Program AST -> canonical model JSON (same shape the Java target emits).
fn program_to_model(prog: &Program, source_text: &str) -> Value {
    let mut reactors = Vec::new();
    for r in &prog.reactors {
        let params: Vec<Value> = r
            .params
            .iter()
            .map(|p| json!({"name": p.name, "default": p.default}))
            .collect();
        let mut states = Vec::new();
        let mut timers = Vec::new();
        let mut inputs = Vec::new();
        let mut outputs = Vec::new();
        let mut actions = Vec::new();
        let mut reactions = Vec::new();
        let mut instances = Vec::new();
        let mut connections = Vec::new();
        for m in &r.members {
            match m {
                Member::State { name, init } => {
                    states.push(json!({"name": name, "init": init}));
                }
                Member::Timer { name, offset, period } => {
                    if let Some(a) = name.strip_prefix("__action_") {
                        actions.push(json!(a));
                    } else {
                        timers.push(json!({
                            "name": name,
                            "offset": {"amount": offset.amount, "unit": offset.unit},
                            "period": {"amount": period.amount, "unit": period.unit},
                        }));
                    }
                }
                Member::Port { name, is_input } => {
                    if *is_input {
                        inputs.push(json!(name));
                    } else {
                        outputs.push(json!(name));
                    }
                }
                Member::Reaction(rx) => {
                    reactions.push(json!({
                        "triggers": rx.triggers,
                        "effects": rx.effects,
                        "body": rx.body,
                        "line": rx.line,
                    }));
                }
                Member::Instance { var, reactor, args } => {
                    let args_json: Vec<Value> = args
                        .iter()
                        .map(|(k, v)| {
                            let val = match v {
                                ArgVal::Int(n) => json!({"int": n}),
                                ArgVal::Ref(r) => json!({"ref": r}),
                            };
                            json!([k, val])
                        })
                        .collect();
                    instances.push(json!({"name": var, "reactor": reactor, "args": args_json}));
                }
                Member::Connection { src_inst, src_port, dst_inst, dst_port } => {
                    connections.push(json!({
                        "src_inst": src_inst, "src_port": src_port,
                        "dst_inst": dst_inst, "dst_port": dst_port,
                    }));
                }
            }
        }
        reactors.push(json!({
            "name": r.name,
            "is_main": r.is_main,
            "params": params,
            "states": states,
            "timers": timers,
            "inputs": inputs,
            "outputs": outputs,
            "actions": actions,
            "reactions": reactions,
            "instances": instances,
            "connections": connections,
        }));
    }
    json!({
        "format": "chrono-model",
        "version": 1,
        "target": prog.target,
        "source_text": source_text,
        "lfc_version": "dump-model",
        "chronoc_version": env!("CARGO_PKG_VERSION"),
        "binding_profile": "hive",
        "config": {"params": [], "capacities": [], "mb_bw": 9, "depth_bw": 10},
        "reactors": reactors,
    })
}

fn lower_model(args: &[String]) -> ExitCode {
    // lower-model <model.json> -o <out.cspec> [--emit-ir <ir.json>]
    let mut model_path: Option<String> = None;
    let mut out_path: Option<String> = None;
    let mut ir_path: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-o" => {
                i += 1;
                out_path = args.get(i).cloned();
            }
            "--emit-ir" => {
                i += 1;
                ir_path = args.get(i).cloned();
            }
            other if !other.starts_with('-') && model_path.is_none() => {
                model_path = Some(other.to_string());
            }
            other => return fail(&format!("unknown argument {other:?}")),
        }
        i += 1;
    }
    let (Some(model_path), Some(out_path)) = (model_path, out_path) else {
        return fail("usage: lower-model <model.json> -o <out.cspec> [--emit-ir <ir.json>]");
    };
    let text = match std::fs::read_to_string(&model_path) {
        Ok(t) => t,
        Err(e) => return fail(&format!("cannot read {model_path}: {e}")),
    };
    let model: Value = match serde_json::from_str(&text) {
        Ok(v) => v,
        Err(e) => return fail(&format!("invalid model JSON: {e}")),
    };
    if as_str(get(&model, "format")) != "chrono-model" {
        return fail("not a chrono-model document");
    }
    let profile = as_str(get(&model, "binding_profile"));
    if profile != "hive" && profile != "fabric" {
        return fail(&format!("unknown binding profile {profile:?}"));
    }
    let prog = model_to_program(&model);
    let cfg_json = get(&model, "config");
    let mut capacities: Vec<(String, u32)> = Vec::new();
    for c in get(cfg_json, "capacities").as_array().unwrap() {
        let pair = c.as_array().unwrap();
        capacities.push((as_str(&pair[0]), as_i64(&pair[1]) as u32));
    }
    let cfg = Config {
        params: Vec::new(),
        capacities,
        mb_bw: as_i64(get(cfg_json, "mb_bw")) as u32,
        depth_bw: as_i64(get(cfg_json, "depth_bw")) as u32,
    };
    let manifest = EffectorManifest::default();
    let source_text = as_str(get(&model, "source_text"));
    let model_blob = match lower::lower(&LowerInput {
        prog: &prog,
        source: &source_text,
        cfg: &cfg,
        lfc_version: as_str(get(&model, "lfc_version")),
        manifest: &manifest,
    }) {
        Ok(m) => m,
        Err(e) => return fail(&format!("{e}")),
    };
    // Binding-profile validation (compile time only; the artifact records
    // nothing profile-specific): every effector the program needs must exist
    // in the named profile's manifest. In this increment both profiles declare
    // the REQ-103 effector set (hardware endpoints on fabric, software
    // callables on hive) — the same check chronoc performs via --manifest.
    let profile_effectors: [&str; 3] = ["train_step", "checkpoint_write", "prefetch_read"];
    for op in &model_blob.operations {
        if !profile_effectors.contains(&op.effector.as_str()) {
            return fail(&format!(
                "op {}: effector {:?} is not declared in the {profile:?} binding profile",
                op.id, op.effector
            ));
        }
    }
    // The Rust writer emits the settled format directly: magic CSP1,
    // CRC-32 trailer over the body. No post-processing (the provisional
    // CSF1 magic rewrite this harness once applied was removed by the
    // coordinated CSP1 rename pass).
    let bytes = blob::write_blob(&model_blob);
    if let Err(e) = std::fs::write(&out_path, &bytes) {
        return fail(&format!("cannot write {out_path}: {e}"));
    }
    if let Some(ir_path) = &ir_path {
        // The Rust IR module names the debug form "cspec-ir" (the settled
        // rename has landed on the toolchain side); emit it unchanged.
        let json_text = ir::to_json(&model_blob);
        if let Err(e) = std::fs::write(ir_path, json_text) {
            return fail(&format!("cannot write {ir_path}: {e}"));
        }
    }
    eprintln!(
        "wrote {} ({} ops, {} steps, {} bytes)",
        out_path,
        model_blob.operations.len(),
        model_blob.schedule.len(),
        bytes.len()
    );
    ExitCode::SUCCESS
}

fn dump_model(args: &[String]) -> ExitCode {
    // dump-model <source.lf> -o <model.json>
    let mut src_path: Option<String> = None;
    let mut out_path: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-o" => {
                i += 1;
                out_path = args.get(i).cloned();
            }
            other if !other.starts_with('-') && src_path.is_none() => {
                src_path = Some(other.to_string());
            }
            other => return fail(&format!("unknown argument {other:?}")),
        }
        i += 1;
    }
    let (Some(src_path), Some(out_path)) = (src_path, out_path) else {
        return fail("usage: dump-model <source.lf> -o <model.json>");
    };
    let source = match std::fs::read_to_string(&src_path) {
        Ok(t) => t,
        Err(e) => return fail(&format!("cannot read {src_path}: {e}")),
    };
    let toks = match lexer::lex(&source) {
        Ok(t) => t,
        Err(e) => return fail(&format!("{e}")),
    };
    let prog = match parser::parse(toks) {
        Ok(p) => p,
        Err(e) => return fail(&format!("{e}")),
    };
    let model = program_to_model(&prog, &source);
    match std::fs::write(&out_path, serde_json::to_string_pretty(&model).unwrap() + "\n") {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => fail(&format!("cannot write {out_path}: {e}")),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(|s| s.as_str()) {
        Some("lower-model") => lower_model(&args[1..]),
        Some("dump-model") => dump_model(&args[1..]),
        _ => fail("usage: chrono-lower-model-proof <lower-model|dump-model> ..."),
    }
}
