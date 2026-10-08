package org.lflang.generator.chrono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lflang.generator.chrono.ChronoBody.Assign;
import org.lflang.generator.chrono.ChronoBody.Expr;
import org.lflang.generator.chrono.ChronoBody.Ge;
import org.lflang.generator.chrono.ChronoBody.If;
import org.lflang.generator.chrono.ChronoBody.Incr;
import org.lflang.generator.chrono.ChronoBody.IntExpr;
import org.lflang.generator.chrono.ChronoBody.Intrinsic;
import org.lflang.generator.chrono.ChronoBody.ModZero;
import org.lflang.generator.chrono.ChronoBody.PortSet;
import org.lflang.generator.chrono.ChronoBody.RawExpr;
import org.lflang.generator.chrono.ChronoBody.RefExpr;
import org.lflang.generator.chrono.ChronoBody.RequestStop;
import org.lflang.generator.chrono.ChronoBody.Stmt;
import org.lflang.lf.BuiltinTriggerRef;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.TriggerRef;
import org.lflang.lf.VarRef;

/**
 * Lowering from the real LF AST / reactor-instance graph to the CSF1 blob model (spec 002,
 * REQ-103..106): a Java port of chronoc's {@code lower.rs}, with identical semantics. It recognizes
 * the driver pattern (periodic timer + counter + stop guard), computes per-reaction fire steps via
 * port dataflow, maps intrinsics to operation templates with cost-model demands, extracts
 * dependencies from connections, and unrolls the schedule.
 *
 * <p>All program structure comes from the LF compiler's own model: reactor instances, resolved
 * integer parameter values, timer periods, ports, and connections are read from the {@code
 * ReactorInstance} graph and the {@code org.lflang.lf} AST. Only reaction <em>body text</em> is
 * parsed (by {@link ChronoBody}), exactly the raw host code every LF target receives. Anything
 * outside the documented subset is rejected with a precise error; there is no silent
 * miscompilation.
 */
public final class ChronoLowering {

  /** A lowering failure: the program is outside the ChronoHive LF subset v1. */
  public static final class SubsetException extends Exception {
    public SubsetException(String msg) {
      super(msg);
    }
  }

  private static SubsetException lerr(String msg) {
    return new SubsetException("lowering error: " + msg);
  }

  // ---------------------------------------------------------------------------
  // Intrinsic table (spec 002, REQ-103; the default effector manifest of REQ-112)
  // ---------------------------------------------------------------------------

  /** Cost model: storage_bw units per checkpoint MB (chronoc {@code --mb-bw} default). */
  public static final long MB_BW = 9;

  /** Cost model: storage_bw units per prefetch depth (chronoc {@code --depth-bw} default). */
  public static final long DEPTH_BW = 10;

  private record DemandSpec(String resource, String arg, long scale) {}

  private record IntrinsicSpec(
      String effector,
      int onRefuse,
      boolean noArgs,
      List<String> requiredArgs,
      List<DemandSpec> demands) {}

  private static final Map<String, IntrinsicSpec> MANIFEST = new LinkedHashMap<>();

  static {
    MANIFEST.put(
        "train_step",
        new IntrinsicSpec("train_step", ChronoCsf.MUST_ADMIT, true, List.of(), List.of()));
    MANIFEST.put(
        "admit_checkpoint",
        new IntrinsicSpec(
            "checkpoint_write",
            ChronoCsf.RETRY,
            false,
            List.of("mb"),
            List.of(new DemandSpec("storage_bw", "mb", MB_BW))));
    MANIFEST.put(
        "prefetch",
        new IntrinsicSpec(
            "prefetch_read",
            ChronoCsf.DROP,
            false,
            List.of("depth"),
            List.of(new DemandSpec("storage_bw", "depth", DEPTH_BW))));
  }

  /**
   * The effectors each binding profile declares in this increment. Both profiles declare the same
   * REQ-103 effectors: on ChronoFabric they are bound to hardware endpoints, on ChronoHive to
   * software callables. The profile check is validation only; it never alters the blob.
   */
  public static final Map<String, Set<String>> PROFILE_EFFECTORS =
      Map.of(
          "hive", Set.of("train_step", "checkpoint_write", "prefetch_read"),
          "fabric", Set.of("train_step", "checkpoint_write", "prefetch_read"));

  // ---------------------------------------------------------------------------
  // Instance analysis
  // ---------------------------------------------------------------------------

  /** One expanded reactor instance, mirroring chronoc's {@code Instance}. */
  public static final class Inst {
    public int idx;
    public String path;
    public Reactor def;
    public final Map<String, Long> params = new LinkedHashMap<>();
    public final Map<String, Long> timers = new LinkedHashMap<>(); // name -> period ns
    public final Set<String> states = new HashSet<>();
    public final Map<String, Boolean> ports = new LinkedHashMap<>(); // name -> isInput
    /** Parsed reaction bodies, parallel to {@link #reactionAsts} (filled by the generator). */
    public final List<List<Stmt>> bodies = new ArrayList<>();
    /** Names of logical actions declared by this reactor (rejected by the subset). */
    public final Set<String> actions = new HashSet<>();
    public final List<Reaction> reactionAsts = new ArrayList<>();
  }

  private static final class Site {
    String name;
    List<Map.Entry<String, Expr>> args;
    Expr guardMod; // nullable
    int line;
    int pos;
  }

  private static final class SetSite {
    String port;
    Expr guardMod; // nullable
    int line;
    int pos;
  }

  private static final class ReactionInfo {
    final List<String> triggers = new ArrayList<>();
    final List<Site> intrinsics = new ArrayList<>();
    final List<SetSite> sets = new ArrayList<>();
    String driverCounter; // nullable
    Expr driverBound; // nullable
    String incrVar; // nullable
    int incrPos = -1;
    String timerTrigger; // nullable
  }

  private static long resolveExpr(Expr e, Map<String, Long> params, String what)
      throws SubsetException {
    if (e instanceof IntExpr i) {
      return i.value();
    }
    if (e instanceof RefExpr r) {
      Long v = params.get(r.name());
      if (v == null) {
        throw lerr(what + ": unknown parameter \"" + r.name() + "\"");
      }
      return v;
    }
    if (e instanceof RawExpr raw) {
      throw lerr(what + ": expected integer, got \"" + raw.text() + "\"");
    }
    throw new AssertionError("unknown Expr: " + e);
  }

  // ---------------------------------------------------------------------------
  // Body analysis (port of chronoc's analyze_reaction)
  // ---------------------------------------------------------------------------

  private static ReactionInfo analyzeReaction(Inst inst, Reaction r, List<Stmt> stmts)
      throws SubsetException {
    ReactionInfo info = new ReactionInfo();
    for (TriggerRef tr : r.getTriggers()) {
      if (tr instanceof BuiltinTriggerRef) {
        throw lerr(
            "reaction: builtin triggers (startup/shutdown) are not supported in subset v1");
      }
      VarRef vr = (VarRef) tr;
      String name = vr.getVariable().getName();
      info.triggers.add(name);
      if (inst.timers.containsKey(name)) {
        if (info.timerTrigger != null) {
          throw lerr("reaction: multiple timer triggers (subset v1 supports one)");
        }
        info.timerTrigger = name;
      } else if (inst.actions.contains(name)) {
        throw lerr(
            "reaction: logical actions are not supported in subset v1 (timers only)");
      }
    }
    int[] pos = {0};
    walk(stmts, null, info, inst.def.getName(), pos);
    return info;
  }

  private static void walk(
      List<Stmt> stmts, Expr guard, ReactionInfo info, String instName, int[] pos)
      throws SubsetException {
    for (Stmt s : stmts) {
      if (s instanceof Intrinsic c) {
        Site site = new Site();
        site.name = c.name();
        site.args = c.args();
        site.guardMod = guard;
        site.line = c.line();
        site.pos = pos[0]++;
        info.intrinsics.add(site);
      } else if (s instanceof Incr inc) {
        if (info.incrVar != null) {
          throw lerr(
              instName
                  + ": reaction at line "
                  + inc.line()
                  + ": multiple counter increments");
        }
        info.incrVar = inc.var();
        info.incrPos = pos[0]++;
      } else if (s instanceof Assign a) {
        throw lerr(
            instName
                + ": reaction at line "
                + a.line()
                + ": only '<counter> = <counter> + 1' assignments are supported (got "
                + a.var()
                + ")");
      } else if (s instanceof PortSet ps) {
        SetSite site = new SetSite();
        site.port = ps.port();
        site.guardMod = guard;
        site.line = ps.line();
        site.pos = pos[0]++;
        info.sets.add(site);
      } else if (s instanceof RequestStop) {
        pos[0]++;
      } else if (s instanceof If ifs) {
        if (ifs.cond() instanceof ModZero mz) {
          if (guard != null) {
            throw lerr(
                instName + ": reaction at line " + ifs.line() + ": nested guards are not supported");
          }
          walk(ifs.body(), mz.modulus(), info, instName, pos);
        } else if (ifs.cond() instanceof Ge ge) {
          boolean hasStop =
              ifs.body().stream().anyMatch(b -> b instanceof RequestStop);
          if (!hasStop) {
            throw lerr(
                instName
                    + ": reaction at line "
                    + ifs.line()
                    + ": '>=' guards are only supported for the stop condition"
                    + " (with request_stop())");
          }
          if (info.driverCounter != null) {
            throw lerr(
                instName + ": reaction at line " + ifs.line() + ": multiple stop guards");
          }
          info.driverCounter = ge.var();
          info.driverBound = ge.bound();
          // request_stop itself carries no op; the rest of a '>=' body is not walked,
          // exactly as in chronoc.
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Main lowering
  // ---------------------------------------------------------------------------

  /**
   * Lower the expanded instances to a blob model.
   *
   * @param instances Instances in expansion order (main first, then children recursively), with
   *     params/timers/states/ports filled from the instance graph and reaction bodies parsed.
   * @param connections Validated connections, as (dstPath, dstPort) -&gt; list of (srcInstIdx,
   *     srcPort), built by the generator from the AST.
   * @param capacities Declared capacities in declaration order (REQ-105).
   * @param bindingProfile The binding profile to validate effectors against (validation only).
   * @param meta Provenance metadata (the generator fills lfTarget with "Chrono").
   */
  public static ChronoCsf.Model lower(
      List<Inst> instances,
      Map<List<String>, List<Map.Entry<Integer, String>>> connections,
      List<Map.Entry<String, Long>> capacities,
      String bindingProfile,
      ChronoCsf.Meta meta)
      throws SubsetException {

    // Find the driver: exactly one reaction with a periodic timer trigger,
    // a counter increment, and a stop guard.
    int driverInst = -1;
    int driverReaction = -1;
    long nSteps = -1;
    long stepNs = -1;
    for (Inst a : instances) {
      for (int ri = 0; ri < analyses.get(a.idx).size(); ri++) {
        ReactionInfo info = analyses.get(a.idx).get(ri);
        Reaction r = a.reactionAsts.get(ri);
        if (info.timerTrigger != null && info.driverCounter != null) {
          long boundV =
              resolveExpr(info.driverBound, a.params, "stop guard");
          if (boundV <= 0 || boundV > 1_000_000) {
            throw lerr("stop bound must be in 1..=1000000, got " + boundV);
          }
          if (!a.states.contains(info.driverCounter)) {
            throw lerr(
                "\""
                    + info.driverCounter
                    + "\" is not a declared state variable");
          }
          if (info.incrVar == null) {
            throw lerr(
                "driver reaction must increment the counter \""
                    + info.driverCounter
                    + "\" ('<counter> = <counter> + 1')");
          }
          if (!info.incrVar.equals(info.driverCounter)) {
            throw lerr(
                "stop guard uses \""
                    + info.driverCounter
                    + "\" but the reaction increments \""
                    + info.incrVar
                    + "\"");
          }
          for (SetSite set : info.sets) {
            if (set.guardMod != null && set.pos < info.incrPos) {
              throw lerr(
                  "line "
                      + set.line
                      + ": guarded port set appears before the counter increment"
                      + " (the guard would read the pre-increment counter)");
            }
          }
          for (Site site : info.intrinsics) {
            if (site.guardMod != null && site.pos < info.incrPos) {
              throw lerr(
                  "line "
                      + site.line
                      + ": guarded intrinsic appears before the counter increment");
            }
          }
          if (driverInst >= 0) {
            throw lerr("multiple driver reactions found (subset v1 supports one timed loop)");
          }
          driverInst = a.idx;
          driverReaction = ri;
          nSteps = boundV;
          stepNs = a.timers.get(info.timerTrigger);
        } else if (info.timerTrigger != null) {
          throw lerr(
              "timer-driven reaction without a counter + stop guard (subset v1 requires"
                  + " '<c> = <c> + 1' and 'if <c> >= <param>: request_stop()')");
        } else if (info.driverCounter != null) {
          throw lerr("stop guard without a timer trigger (only the driver loop may use it)");
        }
      }
    }
    if (driverInst < 0) {
      throw lerr(
          "no driver loop found: subset v1 requires one reaction on a periodic timer that"
              + " increments a state counter with 'if <counter> >= <param>: request_stop()'");
    }
    long n = nSteps;

    // Fire steps per (instance, reaction): driver -> 1..=n; others via dataflow.
    Map<List<Integer>, List<Long>> fire = new HashMap<>();
    List<Long> driverSteps = new ArrayList<>();
    for (long s = 1; s <= n; s++) {
      driverSteps.add(s);
    }
    fire.put(List.of(driverInst, driverReaction), driverSteps);

    // Fixpoint over dataflow.
    boolean changed = true;
    int iters = 0;
    while (changed) {
      changed = false;
      if (++iters > 100) {
        throw lerr("dataflow fixpoint did not converge");
      }
      for (Inst a : instances) {
        for (int ri = 0; ri < analyses.get(a.idx).size(); ri++) {
          if (fire.containsKey(List.of(a.idx, ri))) {
            continue;
          }
          ReactionInfo info = analyses.get(a.idx).get(ri);
          List<Long> steps = new ArrayList<>();
          boolean anyPort = false;
          for (String trig : info.triggers) {
            if (a.timers.containsKey(trig)) {
              continue; // handled: only the driver may use timers
            }
            anyPort = true;
            List<Map.Entry<Integer, String>> srcs =
                connections.getOrDefault(List.of(a.path, trig), List.of());
            for (Map.Entry<Integer, String> src : srcs) {
              int sIdx = src.getKey();
              String sPort = src.getValue();
              Inst sInst = instances.get(sIdx);
              for (int sRi = 0; sRi < analyses.get(sIdx).size(); sRi++) {
                List<Long> sFire = fire.get(List.of(sIdx, sRi));
                if (sFire == null) {
                  continue;
                }
                ReactionInfo sInfo = analyses.get(sIdx).get(sRi);
                for (SetSite set : sInfo.sets) {
                  if (!set.port.equals(sPort)) {
                    continue;
                  }
                  List<Long> ss = new ArrayList<>(sFire);
                  if (set.guardMod != null) {
                    long k = resolveExpr(set.guardMod, sInst.params, "modulo guard");
                    if (k <= 0) {
                      throw lerr("line " + set.line + ": modulo guard must be positive");
                    }
                    ss.removeIf(s -> s % k != 0);
                  }
                  steps.addAll(ss);
                }
              }
            }
          }
          if (anyPort) {
            steps.sort(null);
            List<Long> dedup = new ArrayList<>();
            for (Long s : steps) {
              if (dedup.isEmpty() || !dedup.get(dedup.size() - 1).equals(s)) {
                dedup.add(s);
              }
            }
            if (dedup.isEmpty()) {
              throw lerr(
                  "reaction on port(s) "
                      + info.triggers
                      + " is never triggered (no upstream reaction sets a connected port)");
            }
            fire.put(List.of(a.idx, ri), dedup);
            changed = true;
          } else if (!info.triggers.isEmpty()) {
            throw lerr(
                "reaction triggers "
                    + info.triggers
                    + " are neither a timer nor connected ports");
          }
        }
      }
    }

    // Build operations: one per (instance, intrinsic name).
    Map<List<Object>, Integer> opIndex = new HashMap<>();
    List<ChronoCsf.Op> operations = new ArrayList<>();
    List<Map.Entry<String, Integer>> bindings = new ArrayList<>();
    // (instIdx, reactionIdx) -> list of (opIdx, guardK or null, pos), in creation order.
    Map<List<Integer>, List<long[]>> reactionOps = new LinkedHashMap<>();

    // Deterministic op creation order: driver instance first, then others by path.
    List<Inst> order = new ArrayList<>(instances);
    order.sort(
        Comparator.comparingInt((Inst i) -> i.idx == driverInst ? 0 : 1)
            .thenComparing(i -> i.path));

    for (Inst a : order) {
      for (int ri = 0; ri < analyses.get(a.idx).size(); ri++) {
        ReactionInfo info = analyses.get(a.idx).get(ri);
        Reaction r = a.reactionAsts.get(ri);
        for (Site site : info.intrinsics) {
          IntrinsicSpec spec = MANIFEST.get(site.name);
          if (spec == null) {
            throw lerr(
                "line "
                    + site.line
                    + ": unknown intrinsic \""
                    + site.name
                    + "\" (manifest declares: "
                    + String.join(", ", MANIFEST.keySet())
                    + ")");
          }
          if (spec.noArgs() && !site.args.isEmpty()) {
            throw lerr("line " + site.line + ": " + site.name + "() takes no arguments");
          }
          for (String req : spec.requiredArgs()) {
            getKwarg(site.args, req, site.line, site.name);
          }
          List<Object> key = List.of(a.idx, site.name);
          Integer existing = opIndex.get(key);
          int opIdx;
          if (existing != null) {
            opIdx = existing;
          } else {
            List<ChronoCsf.Demand> demands = new ArrayList<>();
            for (DemandSpec d : spec.demands()) {
              Expr argExpr = getKwarg(site.args, d.arg(), site.line, site.name);
              long v = resolveExpr(argExpr, a.params, site.name + "(" + d.arg() + ")");
              if (v <= 0) {
                throw lerr("line " + site.line + ": " + d.arg() + " must be positive");
              }
              long units = Math.min(Integer.MAX_VALUE, v * d.scale());
              demands.add(new ChronoCsf.Demand(d.resource(), units));
            }
            String id = a.path.isEmpty() ? "main." + site.name : a.path + "." + site.name;
            opIdx = operations.size();
            operations.add(
                new ChronoCsf.Op(id, spec.effector(), spec.onRefuse(), demands, List.of(), opIdx));
            opIndex.put(key, opIdx);
            String lfPath =
                (a.path.isEmpty() ? "main" : a.path)
                    + "."
                    + a.def.getName()
                    + "::reaction("
                    + String.join(",", info.triggers)
                    + ")::"
                    + site.name;
            bindings.add(Map.entry(lfPath, opIdx));
          }
          Long guardK = null;
          if (site.guardMod != null) {
            long k = resolveExpr(site.guardMod, a.params, "modulo guard");
            if (k <= 0) {
              throw lerr("line " + site.line + ": modulo guard must be positive");
            }
            guardK = k;
          }
          reactionOps
              .computeIfAbsent(List.of(a.idx, ri), kk -> new ArrayList<>())
              .add(new long[] {opIdx, guardK == null ? -1 : guardK, site.pos});
        }
      }
    }

    // Dependencies from connections: downstream op depends on upstream op.
    List<List<Integer>> deps = new ArrayList<>();
    for (int i = 0; i < operations.size(); i++) {
      deps.add(new ArrayList<>());
    }
    for (Map.Entry<List<String>, List<Map.Entry<Integer, String>>> e : connections.entrySet()) {
      String dstPath = e.getKey().get(0);
      String dstPort = e.getKey().get(1);
      int dstIdx = -1;
      for (Inst in : instances) {
        if (in.path.equals(dstPath)) {
          dstIdx = in.idx;
          break;
        }
      }
      if (dstIdx < 0) {
        throw lerr("connection: unknown instance \"" + dstPath + "\"");
      }
      for (Map.Entry<Integer, String> src : e.getValue()) {
        int sIdx = src.getKey();
        String sPort = src.getValue();
        List<Integer> upOps = new ArrayList<>();
        Inst sInst = instances.get(sIdx);
        for (int sRi = 0; sRi < analyses.get(sIdx).size(); sRi++) {
          ReactionInfo sInfo = analyses.get(sIdx).get(sRi);
          boolean setsPort = sInfo.sets.stream().anyMatch(st -> st.port.equals(sPort));
          if (setsPort) {
            List<long[]> ops = reactionOps.get(List.of(sIdx, sRi));
            if (ops != null) {
              for (long[] o : ops) {
                upOps.add((int) o[0]);
              }
            }
          }
        }
        Inst dInst = instances.get(dstIdx);
        for (int dRi = 0; dRi < analyses.get(dstIdx).size(); dRi++) {
          ReactionInfo dInfo = analyses.get(dstIdx).get(dRi);
          if (!dInfo.triggers.contains(dstPort)) {
            continue;
          }
          List<long[]> ops = reactionOps.get(List.of(dstIdx, dRi));
          if (ops == null) {
            continue;
          }
          for (long[] dOp : ops) {
            for (int uOp : upOps) {
              if (!deps.get((int) dOp[0]).contains(uOp)) {
                deps.get((int) dOp[0]).add(uOp);
              }
            }
          }
        }
      }
    }
    // Sort deps for determinism; rebuild ops with deps; check acyclicity.
    List<ChronoCsf.Op> finalOps = new ArrayList<>();
    for (int i = 0; i < operations.size(); i++) {
      ChronoCsf.Op op = operations.get(i);
      List<Integer> d = new ArrayList<>(deps.get(i));
      d.sort(null);
      finalOps.add(new ChronoCsf.Op(op.id(), op.effector(), op.onRefuse(), op.demands(), d, op.order()));
    }
    if (topoOrder(range(finalOps.size()), finalOps).size() != finalOps.size()) {
      throw lerr("dependency cycle between operations");
    }

    // Validate demands against capacities.
    Set<String> capNames = new HashSet<>();
    for (Map.Entry<String, Long> c : capacities) {
      capNames.add(c.getKey());
    }
    for (ChronoCsf.Op op : finalOps) {
      for (ChronoCsf.Demand d : op.demands()) {
        if (!capNames.contains(d.resource())) {
          throw lerr(
              "op "
                  + op.id()
                  + ": demand on undeclared resource \""
                  + d.resource()
                  + "\" (declare it in the target block: capacities: \""
                  + d.resource()
                  + "=<units>\")");
        }
      }
    }

    // Binding-profile validation (compile time only; the blob records nothing
    // profile-specific and stays engine-neutral).
    Set<String> profileEffectors = PROFILE_EFFECTORS.get(bindingProfile);
    if (profileEffectors == null) {
      throw lerr("unknown binding profile \"" + bindingProfile + "\"");
    }
    for (ChronoCsf.Op op : finalOps) {
      if (!profileEffectors.contains(op.effector())) {
        throw lerr(
            "op "
                + op.id()
                + ": effector \""
                + op.effector()
                + "\" is not declared in the \""
                + bindingProfile
                + "\" binding profile");
      }
    }

    // Unroll the schedule.
    List<ChronoCsf.Step> schedule = new ArrayList<>();
    for (long s = 1; s <= n; s++) {
      List<Integer> ops = new ArrayList<>();
      for (Map.Entry<List<Integer>, List<long[]>> e : reactionOps.entrySet()) {
        List<Long> steps = fire.getOrDefault(e.getKey(), List.of());
        if (!steps.contains(s)) {
          continue;
        }
        for (long[] rOp : e.getValue()) {
          long guardK = rOp[1];
          if (guardK >= 0 && s % guardK != 0) {
            continue;
          }
          int opIdx = (int) rOp[0];
          if (!ops.contains(opIdx)) {
            ops.add(opIdx);
          }
        }
      }
      List<Integer> ordered = topoOrder(ops, finalOps);
      if (ordered.isEmpty() && !ops.isEmpty()) {
        throw lerr("dependency cycle within a schedule step");
      }
      schedule.add(new ChronoCsf.Step((int) s, ordered));
    }

    ChronoCsf.Model model = new ChronoCsf.Model();
    model.capacities.addAll(capacities);
    model.operations.addAll(finalOps);
    model.schedule.addAll(schedule);
    model.bindings.addAll(bindings);
    model.meta =
        new ChronoCsf.Meta(
            meta.lfSha256(), meta.lfcVersion(), meta.chronocVersion(), meta.lfTarget(),
            (int) n, stepNs);
    return model;
  }

  private static List<Integer> range(int n) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(i);
    }
    return out;
  }

  private static Expr getKwarg(
      List<Map.Entry<String, Expr>> args, String name, int line, String intrinsic)
      throws SubsetException {
    for (Map.Entry<String, Expr> e : args) {
      if (e.getKey().equals(name)) {
        return e.getValue();
      }
    }
    throw lerr("line " + line + ": " + intrinsic + "() requires '" + name + "='");
  }

  /** Kahn's algorithm on the induced subgraph, tie-broken by op creation order (as chronoc). */
  private static List<Integer> topoOrder(List<Integer> ops, List<ChronoCsf.Op> operations) {
    Set<Integer> set = new HashSet<>(ops);
    Map<Integer, Integer> indeg = new HashMap<>();
    Map<Integer, List<Integer>> dependents = new HashMap<>();
    for (int o : ops) {
      int deg = 0;
      for (int d : operations.get(o).deps()) {
        if (set.contains(d)) {
          deg++;
          dependents.computeIfAbsent(d, kk -> new ArrayList<>()).add(o);
        }
      }
      indeg.put(o, deg);
    }
    List<Integer> ready = new ArrayList<>();
    for (int o : ops) {
      if (indeg.get(o) == 0) {
        ready.add(o);
      }
    }
    ready.sort(Comparator.comparingInt(o -> operations.get(o).order()));
    List<Integer> out = new ArrayList<>();
    while (!ready.isEmpty()) {
      int o = ready.remove(0);
      out.add(o);
      List<Integer> ds = dependents.getOrDefault(o, List.of());
      List<Integer> sorted = new ArrayList<>(ds);
      sorted.sort(Comparator.comparingInt(x -> operations.get(x).order()));
      for (int d : sorted) {
        int nd = indeg.merge(d, -1, Integer::sum);
        if (nd == 0) {
          ready.add(d);
          ready.sort(Comparator.comparingInt(x -> operations.get(x).order()));
        }
      }
    }
    if (out.size() != ops.size()) {
      return List.of(); // cycle
    }
    return out;
  }
}
