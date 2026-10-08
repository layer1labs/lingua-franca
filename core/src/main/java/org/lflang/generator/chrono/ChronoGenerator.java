package org.lflang.generator.chrono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.util.RuntimeIOException;
import org.lflang.LocalStrings;
import org.lflang.ast.ASTUtils;
import org.lflang.generator.GeneratorBase;
import org.lflang.generator.LFGeneratorContext;
import org.lflang.generator.TargetTypes;
import org.lflang.generator.docker.DockerGenerator;
import org.lflang.generator.python.PythonTypes;
import org.lflang.lf.BuiltinTriggerRef;
import org.lflang.lf.CodeExpr;
import org.lflang.lf.Connection;
import org.lflang.lf.Expression;
import org.lflang.lf.Initializer;
import org.lflang.lf.Literal;
import org.lflang.lf.Model;
import org.lflang.lf.Parameter;
import org.lflang.lf.ParameterReference;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.StateVar;
import org.lflang.lf.Time;
import org.lflang.lf.Timer;
import org.lflang.lf.TriggerRef;
import org.lflang.lf.VarRef;
import org.lflang.target.Target;
import org.lflang.target.property.BindingProfileProperty;
import org.lflang.target.property.ChronoCapacitiesProperty;
import org.lflang.util.FileUtil;

/**
 * Generator for the Chrono target: the Lingua Franca backend of the ChronoHive / ChronoFabric
 * toolchain.
 *
 * <p>There is exactly one Chrono target, and this generator is deliberately <em>thin</em>, in the
 * same way LF's own targets are thin shells around an external backend compiler (the C target
 * generates code and invokes a C compiler; the Rust target invokes cargo). Everything Layer1Labs
 * owns is Rust: the single lowering / schedule-unrolling / CSF-emission implementation lives in
 * Rust chronoc, and this Java class never reimplements it. The generator's job is:
 *
 * <ol>
 *   <li>extract the compiled program from the <b>real LF AST</b> ({@code org.lflang.lf}) — the
 *       reactor-instance graph is also built (via {@code GeneratorBase} and {@code
 *       ASTUtils.createMainReactorInstance}) as the compiler's own structural gate — into
 *       chronoc's <b>canonical model</b> ({@code chrono-model} JSON, schema below);
 *   <li>validate the ChronoHive LF subset v1 (spec 002, REQ-102) against that AST, rejecting
 *       anything outside it with a precise error — this class is the gatekeeper before lowering;
 *   <li>invoke the Rust backend: {@code chronoc lower-model <model.json> -o <name>.csf
 *       --emit-ir <name>.csf-ir.json}.
 * </ol>
 *
 * <p>The artifact produced by the backend is a deterministic binary in the Constraint
 * Specification Format ({@code .csf}, magic {@code CSF1}): the operations, capacities, unrolled
 * schedule, dependencies, bindings, and provenance an engine needs (spec 002, REQ-103..106,
 * REQ-113). The generator does not build an engine. The same artifact runs on the ChronoHive
 * engine (software effector binding) and the ChronoFabric engine (hardware effector binding);
 * engines differ only in effector binding.
 *
 * <p>Locating chronoc: the {@code chrono.chronoc} system property, else the {@code CHRONOC}
 * environment variable, else {@code chronoc} on the {@code PATH}. If no backend is found, the
 * canonical model is still written and compilation fails with an error saying exactly how to
 * provide one.
 *
 * <p>Target properties:
 *
 * <ul>
 *   <li>{@code capacities: "storage_bw=1000"} — declared resource capacities (REQ-105), carried
 *       into the model's config section; the Rust lowering validates demands against them.
 *   <li>{@code binding-profile: hive | fabric} — compile-time validation only: the Rust lowering
 *       checks (through its effector manifest) that every effector the program needs exists in
 *       the named profile. The profile changes nothing in the emitted artifact and is recorded
 *       nowhere in it; the artifact stays engine-neutral ({@code lf_target = "Chrono"}).
 * </ul>
 *
 * <p>Canonical model schema ({@code chrono-model}, version 1) — the complete input of the Rust
 * lowering; it mirrors chronoc's Rust AST one-to-one so the Rust side needs nothing else:
 *
 * <pre>{@code
 * {
 *   "format": "chrono-model", "version": 1,
 *   "target": "Chrono",
 *   "source_text": "<full .lf source text>",   // hashed (SHA-256) by the backend for meta
 *   "lfc_version": "lfc 0.13.0",
 *   "chronoc_version": "0.1.0-alpha.1",
 *   "binding_profile": "hive" | "fabric",
 *   "config": { "params": [],                  // --param overrides (name,value); empty via lfc
 *               "capacities": [["storage_bw", 1000]],
 *               "mb_bw": 9, "depth_bw": 10 },  // REQ-103 cost-model defaults
 *   "reactors": [{
 *     "name": "Trainer", "is_main": false,
 *     "params":  [{"name": "steps", "default": 6}],
 *     "states":  [{"name": "step", "init": 0}],
 *     "timers":  [{"name": "tick",
 *                  "offset": {"amount": 0, "unit": ""},
 *                  "period": {"amount": 1, "unit": "ms"}}],
 *     "inputs":  ["ckpt"], "outputs": ["step_done"],
 *     "actions": [],
 *     "reactions": [{"triggers": ["tick"], "effects": ["step_done"],
 *                    "body": "<raw body text>", "line": 10}],
 *     "instances": [{"name": "t", "reactor": "Trainer",
 *                    "args": [["steps", {"ref": "steps"}]]}],   // value: {"int": n} | {"ref": name}
 *     "connections": [{"src_inst": "t", "src_port": "ckpt_request",
 *                      "dst_inst": "c", "dst_port": "ckpt"}]
 *   }]
 * }
 * }</pre>
 */
public class ChronoGenerator extends GeneratorBase {

  /** Version of the Chrono lowering recorded as {@code chronoc_version} in the artifact meta. */
  public static final String CHRONOC_VERSION = "0.1.0-alpha.1";

  /** REQ-103 cost-model defaults, mirrored in the model's config section. */
  public static final long MB_BW = 9;

  public static final long DEPTH_BW = 10;

  public ChronoGenerator(LFGeneratorContext context) {
    super(context);
  }

  @Override
  public Target getTarget() {
    return Target.Chrono;
  }

  @Override
  public TargetTypes getTargetTypes() {
    // Chrono reaction bodies are written in the Python-syntax subset (REQ-102),
    // so Python's type rendering is the correct one for this target.
    return new PythonTypes();
  }

  @Override
  protected DockerGenerator getDockerGenerator(LFGeneratorContext context) {
    return null; // The Chrono target has no container build; its backend emits one artifact file.
  }

  /** A subset violation found while extracting the model from the AST. */
  private static final class SubsetException extends Exception {
    SubsetException(String msg) {
      super(msg);
    }
  }

  private static SubsetException subset(String msg) {
    return new SubsetException("Chrono subset v1: " + msg);
  }

  @Override
  public void doGenerate(Resource resource, LFGeneratorContext context) {
    super.doGenerate(resource, context);
    if (errorsOccurred()) {
      return;
    }
    if (mainDef == null) {
      messageReporter.nowhere().error("The Chrono target requires a main reactor.");
      return;
    }
    // Build the reactor-instance graph as the compiler's structural gate
    // (instantiation resolution, causality). Extraction below reads the AST;
    // the graph guarantees that what we extract is a well-formed program.
    this.main =
        ASTUtils.createMainReactorInstance(mainDef, reactors, messageReporter, targetConfig);
    if (errorsOccurred() || this.main == null) {
      return;
    }
    try {
      String modelJson = extractModel(resource);
      Path srcGen = context.getFileConfig().getSrcGenPath();
      Files.createDirectories(srcGen);
      String base = context.getFileConfig().name;
      Path modelPath = srcGen.resolve(base + ".chrono-model.json");
      Files.writeString(modelPath, modelJson);
      Path csfPath = srcGen.resolve(base + ".csf");
      Path irPath = srcGen.resolve(base + ".csf-ir.json");
      runBackend(modelPath, csfPath, irPath);
    } catch (SubsetException e) {
      messageReporter.nowhere().error(e.getMessage());
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Backend invocation (the Rust chronoc lowering is the single implementation)
  // ---------------------------------------------------------------------------

  private void runBackend(Path modelPath, Path csfPath, Path irPath) throws IOException {
    String chronoc = System.getProperty("chrono.chronoc");
    if (chronoc == null || chronoc.isBlank()) {
      chronoc = System.getenv("CHRONOC");
    }
    if (chronoc == null || chronoc.isBlank()) {
      chronoc = "chronoc";
    }
    List<String> cmd =
        List.of(
            chronoc,
            "lower-model",
            modelPath.toString(),
            "-o",
            csfPath.toString(),
            "--emit-ir",
            irPath.toString());
    Process proc;
    try {
      proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    } catch (IOException e) {
      messageReporter
          .nowhere()
          .error(
              "Chrono: wrote the canonical model to "
                  + modelPath
                  + " but could not run the Rust backend '"
                  + chronoc
                  + "'. Point the CHRONOC environment variable (or -Dchrono.chronoc) at a"
                  + " chronoc binary that provides the 'lower-model' subcommand.");
      return;
    }
    String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit;
    try {
      exit = proc.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
    if (exit != 0) {
      messageReporter
          .nowhere()
          .error("Chrono: Rust backend failed (exit " + exit + "):\n" + out.strip());
      return;
    }
    messageReporter.nowhere().info("Chrono: wrote " + csfPath + " via the Rust chronoc backend.");
    if (!out.isBlank()) {
      messageReporter.nowhere().info(out.strip());
    }
  }

  // ---------------------------------------------------------------------------
  // Extraction: real LF AST -> canonical model JSON, with subset validation
  // ---------------------------------------------------------------------------

  private String extractModel(Resource resource) throws SubsetException, IOException {
    Model model = (Model) resource.getContents().get(0);
    if (!model.getImports().isEmpty()) {
      throw subset("imports are not supported (a Chrono program is a single .lf file)");
    }
    byte[] sourceBytes = Files.readAllBytes(FileUtil.toPath(resource));
    String sourceText = new String(sourceBytes, StandardCharsets.UTF_8);

    List<Map.Entry<String, Long>> capacities = parseCapacities();
    String profile = targetConfig.get(BindingProfileProperty.INSTANCE).toString();

    StringBuilder b = new StringBuilder();
    b.append("{\n");
    b.append("  \"format\": \"chrono-model\",\n");
    b.append("  \"version\": 1,\n");
    b.append("  \"target\": \"Chrono\",\n");
    b.append("  \"source_text\": ").append(ChronoModelJson.str(sourceText)).append(",\n");
    b.append("  \"lfc_version\": ").append(ChronoModelJson.str("lfc " + LocalStrings.VERSION)).append(",\n");
    b.append("  \"chronoc_version\": ").append(ChronoModelJson.str(CHRONOC_VERSION)).append(",\n");
    b.append("  \"binding_profile\": ").append(ChronoModelJson.str(profile)).append(",\n");
    b.append("  \"config\": {\"params\": [], \"capacities\": ")
        .append(ChronoModelJson.pairs(capacities))
        .append(", \"mb_bw\": ").append(MB_BW)
        .append(", \"depth_bw\": ").append(DEPTH_BW).append("},\n");
    b.append("  \"reactors\": [");
    boolean first = true;
    for (Reactor reactor : reactors) {
      if (!first) {
        b.append(",");
      }
      first = false;
      b.append("\n    ").append(extractReactor(reactor));
    }
    b.append(reactors.isEmpty() ? "]\n" : "\n  ]\n");
    b.append("}\n");
    return b.toString();
  }

  private String extractReactor(Reactor reactor) throws SubsetException {
    String name = reactor.getName();
    if (!reactor.getSuperClasses().isEmpty()) {
      throw subset("reactor inheritance (extends) is not supported (reactor " + name + ")");
    }
    if (!reactor.getTypeParms().isEmpty()) {
      throw subset("type parameters are not supported (reactor " + name + ")");
    }
    if (reactor.getHost() != null) {
      throw subset("host annotations are not supported (reactor " + name + ")");
    }
    if (!reactor.getPreambles().isEmpty()) {
      throw subset("preambles are not supported (reactor " + name + ")");
    }
    if (!reactor.getMethods().isEmpty()) {
      throw subset("methods are not supported (reactor " + name + ")");
    }
    if (!reactor.getModes().isEmpty()) {
      throw subset("modal reactors are not supported (reactor " + name + ")");
    }
    if (!reactor.getWatchdogs().isEmpty()) {
      throw subset("watchdogs are not supported (reactor " + name + ")");
    }
    if (reactor.isFederated()) {
      throw subset("federated reactors are not supported (reactor " + name + ")");
    }

    StringBuilder b = new StringBuilder();
    b.append("{\"name\": ").append(ChronoModelJson.str(name));
    b.append(", \"is_main\": ").append(reactor.isMain());

    b.append(", \"params\": [");
    boolean first = true;
    for (Parameter p : reactor.getParameters()) {
      if (!first) b.append(", ");
      first = false;
      b.append("{\"name\": ").append(ChronoModelJson.str(p.getName()));
      b.append(", \"default\": ").append(paramDefault(p, name)).append("}");
    }
    b.append("]");

    b.append(", \"states\": [");
    first = true;
    for (StateVar s : reactor.getStateVars()) {
      if (!first) b.append(", ");
      first = false;
      b.append("{\"name\": ").append(ChronoModelJson.str(s.getName()));
      b.append(", \"init\": ").append(stateInit(s, name)).append("}");
    }
    b.append("]");

    b.append(", \"timers\": [");
    first = true;
    for (Timer t : reactor.getTimers()) {
      if (!first) b.append(", ");
      first = false;
      b.append("{\"name\": ").append(ChronoModelJson.str(t.getName()));
      b.append(", \"offset\": ").append(timeVal(t.getOffset(), name, t.getName()));
      b.append(", \"period\": ").append(timeVal(t.getPeriod(), name, t.getName()));
      b.append("}");
    }
    b.append("]");

    List<String> inputs = new ArrayList<>();
    for (var in : reactor.getInputs()) {
      if (in.getWidthSpec() != null) {
        throw subset("multiports are not supported (port " + in.getName() + ")");
      }
      if (in.getType() != null) {
        throw subset("typed ports are not supported (port " + in.getName() + ")");
      }
      inputs.add(in.getName());
    }
    List<String> outputs = new ArrayList<>();
    for (var out : reactor.getOutputs()) {
      if (out.getWidthSpec() != null) {
        throw subset("multiports are not supported (port " + out.getName() + ")");
      }
      if (out.getType() != null) {
        throw subset("typed ports are not supported (port " + out.getName() + ")");
      }
      outputs.add(out.getName());
    }
    b.append(", \"inputs\": ").append(ChronoModelJson.strList(inputs));
    b.append(", \"outputs\": ").append(ChronoModelJson.strList(outputs));

    List<String> actions = new ArrayList<>();
    for (var a : reactor.getActions()) {
      actions.add(a.getName());
    }
    b.append(", \"actions\": ").append(ChronoModelJson.strList(actions));

    b.append(", \"reactions\": [");
    first = true;
    for (Reaction r : reactor.getReactions()) {
      if (!first) b.append(", ");
      first = false;
      b.append(extractReaction(r, name));
    }
    b.append("]");

    b.append(", \"instances\": [");
    first = true;
    for (var inst : reactor.getInstantiations()) {
      if (!first) b.append(", ");
      first = false;
      if (inst.getWidthSpec() != null) {
        throw subset("banks of reactors are not supported (instance " + inst.getName() + ")");
      }
      if (inst.getHost() != null) {
        throw subset("host annotations are not supported (instance " + inst.getName() + ")");
      }
      b.append("{\"name\": ").append(ChronoModelJson.str(inst.getName()));
      b.append(", \"reactor\": ")
          .append(ChronoModelJson.str(ASTUtils.toDefinition(inst.getReactorClass()).getName()));
      b.append(", \"args\": [");
      boolean firstArg = true;
      for (var assignment : inst.getParameters()) {
        if (!firstArg) b.append(", ");
        firstArg = false;
        b.append("[")
            .append(ChronoModelJson.str(assignment.getLhs().getName()))
            .append(", ")
            .append(argValue(assignment.getRhs(), name))
            .append("]");
      }
      b.append("]}");
    }
    b.append("]");

    b.append(", \"connections\": [");
    first = true;
    for (Connection c : reactor.getConnections()) {
      if (c.isPhysical()) {
        throw subset("physical connections (~>) are not supported (reactor " + name + ")");
      }
      if (c.getDelay() != null) {
        throw subset("connection delays (after) are not supported (reactor " + name + ")");
      }
      if (c.getSerializer() != null) {
        throw subset("connection serializers are not supported (reactor " + name + ")");
      }
      if (c.isIterated()) {
        throw subset("iterated (bank) connections are not supported (reactor " + name + ")");
      }
      if (c.getLeftPorts().size() != c.getRightPorts().size()) {
        throw subset("connections with mismatched port counts are not supported (reactor " + name + ")");
      }
      for (int i = 0; i < c.getLeftPorts().size(); i++) {
        if (!first) b.append(", ");
        first = false;
        VarRef src = c.getLeftPorts().get(i);
        VarRef dst = c.getRightPorts().get(i);
        b.append("{\"src_inst\": ")
            .append(ChronoModelJson.str(src.getContainer() == null ? "" : src.getContainer().getName()));
        b.append(", \"src_port\": ").append(ChronoModelJson.str(portNameOf(src, name)));
        b.append(", \"dst_inst\": ")
            .append(ChronoModelJson.str(dst.getContainer() == null ? "" : dst.getContainer().getName()));
        b.append(", \"dst_port\": ").append(ChronoModelJson.str(portNameOf(dst, name)));
        b.append("}");
      }
    }
    b.append("]}");
    return b.toString();
  }

  private String portNameOf(VarRef ref, String reactorName) throws SubsetException {
    if (!(ref.getVariable() instanceof org.lflang.lf.Port)) {
      throw subset(
          "connection endpoint is not a port: \""
              + ref.getVariable().getName()
              + "\" (reactor "
              + reactorName
              + ")");
    }
    if (ref.isInterleaved()) {
      throw subset("interleaved connections are not supported (reactor " + reactorName + ")");
    }
    return ref.getVariable().getName();
  }

  private String extractReaction(Reaction r, String reactorName) throws SubsetException {
    if (r.isMutation()) {
      throw subset("mutations are not supported (reactor " + reactorName + ")");
    }
    if (r.getDeadline() != null || r.getStp() != null || r.getTardy() != null) {
      throw subset("reaction deadlines/STP/tardy handlers are not supported (reactor " + reactorName + ")");
    }
    if (r.getCode() == null) {
      throw subset("reactions without an inlined body are not supported (reactor " + reactorName + ")");
    }
    List<String> triggers = new ArrayList<>();
    for (TriggerRef tr : r.getTriggers()) {
      if (tr instanceof BuiltinTriggerRef) {
        throw subset("builtin triggers (startup/shutdown) are not supported (reactor " + reactorName + ")");
      }
      triggers.add(((VarRef) tr).getVariable().getName());
    }
    List<String> effects = new ArrayList<>();
    for (var e : r.getEffects()) {
      if (e instanceof VarRef vr) {
        effects.add(vr.getVariable().getName());
      } else {
        throw subset("mode transitions as reaction effects are not supported (reactor " + reactorName + ")");
      }
    }
    var node = NodeModelUtils.findActualNodeFor(r);
    int line = node != null ? node.getStartLine() : 1;
    StringBuilder b = new StringBuilder();
    b.append("{\"triggers\": ").append(ChronoModelJson.strList(triggers));
    b.append(", \"effects\": ").append(ChronoModelJson.strList(effects));
    b.append(", \"body\": ").append(ChronoModelJson.str(r.getCode().getBody()));
    b.append(", \"line\": ").append(line).append("}");
    return b.toString();
  }

  // ---------------------------------------------------------------------------
  // Scalar extraction helpers (integer / time subset)
  // ---------------------------------------------------------------------------

  private long paramDefault(Parameter p, String reactorName) throws SubsetException {
    Initializer init = p.getInit();
    if (init != null && init.getExpr() != null) {
      return intLiteral(init.getExpr(), "parameter \"" + p.getName() + "\" of reactor " + reactorName);
    }
    // Typed form with the default inside the type's code, e.g. `x: int(10)`.
    if (p.getType() != null && p.getType().getCode() != null) {
      String body = p.getType().getCode().getBody().strip();
      int open = body.indexOf('(');
      int close = body.lastIndexOf(')');
      if (open >= 0 && close > open) {
        try {
          return Long.parseLong(body.substring(open + 1, close).strip().replace("_", ""));
        } catch (NumberFormatException ignored) {
          // fall through to the error below
        }
      }
    }
    throw subset(
        "parameter \""
            + p.getName()
            + "\" of reactor "
            + reactorName
            + " has no integer default value (subset v1 requires one)");
  }

  private long stateInit(StateVar s, String reactorName) throws SubsetException {
    Initializer init = s.getInit();
    if (init == null || init.getExpr() == null) {
      throw subset(
          "state variable \""
              + s.getName()
              + "\" of reactor "
              + reactorName
              + " has no integer initializer (subset v1 requires one)");
    }
    return intLiteral(init.getExpr(), "state variable \"" + s.getName() + "\" of reactor " + reactorName);
  }

  private long intLiteral(Expression expr, String what) throws SubsetException {
    if (expr instanceof Literal lit) {
      String text = lit.getLiteral().strip().replace("_", "");
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        // fall through to the error below
      }
    }
    if (expr instanceof CodeExpr ce) {
      try {
        return Long.parseLong(ce.getCode().getBody().strip().replace("_", ""));
      } catch (NumberFormatException ignored) {
        // fall through to the error below
      }
    }
    throw subset(what + " is not an integer literal (subset v1 supports integer values only)");
  }

  private String argValue(Initializer rhs, String reactorName) throws SubsetException {
    Expression expr = rhs != null ? rhs.getExpr() : null;
    if (expr instanceof Literal) {
      return "{\"int\": " + intLiteral(expr, "instantiation argument in reactor " + reactorName) + "}";
    }
    if (expr instanceof ParameterReference pr) {
      return "{\"ref\": " + ChronoModelJson.str(pr.getParameter().getName()) + "}";
    }
    throw subset(
        "instantiation arguments must be integer literals or parameter references (reactor "
            + reactorName
            + ")");
  }

  /** Extract a timer offset/period as {amount, unit} with the Rust subset's unit spellings. */
  private String timeVal(Expression expr, String reactorName, String timerName)
      throws SubsetException {
    if (expr == null) {
      throw subset(
          "timer "
              + timerName
              + " of reactor "
              + reactorName
              + " must declare an explicit (offset, period) in subset v1");
    }
    if (expr instanceof Time time) {
      if (time.isForever() || time.isNever()) {
        throw subset(
            "forever/never timers are not supported in subset v1 (timer "
                + timerName
                + " of reactor "
                + reactorName
                + ")");
      }
      if (time.getUnit() == null) {
        // A bare amount carries the subset's default unit (ms), mirroring
        // chronoc's parser convention for unit-less time values.
        return "{\"amount\": " + time.getInterval() + ", \"unit\": \"ms\"}";
      }
      String unit =
          switch (time.getUnit()) {
            case MICRO -> "us";
            case MILLI -> "ms";
            case SECOND -> "s";
            case MINUTE -> "min";
            default ->
                throw subset(
                    "time unit "
                        + time.getUnit()
                        + " is not supported in subset v1 (use us, ms, s, or min; timer "
                        + timerName
                        + " of reactor "
                        + reactorName
                        + ")");
          };
      return "{\"amount\": " + time.getInterval() + ", \"unit\": \"" + unit + "\"}";
    }
    if (expr instanceof Literal) {
      // Unit-less time value (e.g. the conventional `timer t(0, 1 ms)` zero
      // offset): chronoc's parser defaults the unit to ms.
      long v = intLiteral(expr, "timer " + timerName + " of reactor " + reactorName);
      return "{\"amount\": " + v + ", \"unit\": \"ms\"}";
    }
    throw subset(
        "timer "
            + timerName
            + " of reactor "
            + reactorName
            + " must use time literals in subset v1 (offset/period are not expressions)");
  }

  /** Parse the {@code capacities} target property: comma-separated {@code name=units} pairs. */
  private List<Map.Entry<String, Long>> parseCapacities() throws SubsetException {
    String raw = targetConfig.get(ChronoCapacitiesProperty.INSTANCE);
    List<Map.Entry<String, Long>> out = new ArrayList<>();
    if (raw == null || raw.strip().isEmpty()) {
      return out;
    }
    for (String pair : raw.split(",")) {
      String p = pair.strip();
      if (p.isEmpty()) {
        continue;
      }
      int eq = p.indexOf('=');
      if (eq < 0) {
        throw subset("capacities expects name=units pairs, got \"" + p + "\"");
      }
      long units;
      try {
        units = Long.parseLong(p.substring(eq + 1).strip());
      } catch (NumberFormatException e) {
        throw subset("capacities expects unsigned integer units, got \"" + p + "\"");
      }
      if (units < 0 || units > 0xFFFF_FFFFL) {
        throw subset("capacity units out of range in \"" + p + "\"");
      }
      out.add(Map.entry(p.substring(0, eq).strip(), units));
    }
    return out;
  }
}
