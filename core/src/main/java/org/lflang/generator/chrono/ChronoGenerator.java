package org.lflang.generator.chrono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.util.RuntimeIOException;
import org.lflang.LocalStrings;
import org.lflang.ast.ASTUtils;
import org.lflang.generator.GeneratorBase;
import org.lflang.generator.LFGeneratorContext;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.TargetTypes;
import org.lflang.generator.TimerInstance;
import org.lflang.generator.docker.DockerGenerator;
import org.lflang.generator.python.PythonTypes;
import org.lflang.lf.Connection;
import org.lflang.lf.Instantiation;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.VarRef;
import org.lflang.target.Target;
import org.lflang.target.property.BindingProfileProperty;
import org.lflang.target.property.ChronoCapacitiesProperty;
import org.lflang.util.FileUtil;

/**
 * Generator for the Chrono target: the Lingua Franca backend of the ChronoHive / ChronoFabric
 * toolchain (chronoc as a real LF target).
 *
 * <p>There is exactly one Chrono target. This generator's only output is a deterministic binary
 * artifact in the Constraint Specification Format ({@code .csf}, magic {@code CSF1}) — plus its
 * {@code csf-ir} JSON debug form — that engines consume. It does not build an engine, and it does
 * not generate target-language code: the artifact declares the operations, capacities, unrolled
 * schedule, dependencies, bindings, and provenance an engine needs (spec 002, REQ-102..106,
 * REQ-113). The same artifact runs on the ChronoHive engine (software effector binding) and the
 * ChronoFabric engine (hardware effector binding); the engines differ only in effector binding.
 *
 * <p>Program structure is taken from the real LF AST ({@code org.lflang.lf}) and the
 * reactor-instance graph built by the LF compiler — reactors, parameters (resolved through the
 * instance graph), timers, ports, reactions, instantiations, and connections are never re-parsed
 * by hand. Only reaction body text is parsed, by {@link ChronoBody}, because every LF target
 * receives reaction bodies as raw host code; the Chrono body language is the Python-syntax subset
 * of REQ-102/103.
 *
 * <p>Target properties:
 *
 * <ul>
 *   <li>{@code capacities: "storage_bw=1000"} — declared resource capacities (REQ-105), as
 *       comma-separated {@code name=units} pairs.
 *   <li>{@code binding-profile: hive | fabric} — compile-time validation only: the generator
 *       checks that every effector the program needs exists in the named profile's effector
 *       manifest. The profile changes nothing in the emitted artifact and is recorded nowhere in
 *       it; the artifact stays engine-neutral ({@code lf_target = "Chrono"}).
 * </ul>
 */
public class ChronoGenerator extends GeneratorBase {

  /** Version of the Chrono lowering recorded as {@code chronoc_version} in the artifact meta. */
  public static final String CHRONOC_VERSION = "0.1.0-alpha.1";

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
    return null; // The Chrono target has no container build; it emits a single artifact file.
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
    this.main =
        ASTUtils.createMainReactorInstance(mainDef, reactors, messageReporter, targetConfig);
    if (errorsOccurred() || this.main == null) {
      return;
    }
    try {
      ChronoCsf.Model model = buildModel(resource);
      Path srcGen = context.getFileConfig().getSrcGenPath();
      Files.createDirectories(srcGen);
      String base = context.getFileConfig().name;
      Path csfPath = srcGen.resolve(base + ".csf");
      Files.write(csfPath, ChronoCsf.write(model));
      Path irPath = srcGen.resolve(base + ".csf-ir.json");
      Files.writeString(irPath, ChronoIr.toJson(model));
      messageReporter
          .nowhere()
          .info(
              "Chrono: wrote "
                  + csfPath
                  + " ("
                  + model.operations.size()
                  + " ops, "
                  + model.schedule.size()
                  + " steps) and "
                  + irPath);
    } catch (ChronoLowering.SubsetException e) {
      messageReporter.nowhere().error(e.getMessage());
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Model construction from the LF AST / instance graph
  // ---------------------------------------------------------------------------

  private ChronoCsf.Model buildModel(Resource resource)
      throws ChronoLowering.SubsetException, IOException {
    List<ChronoLowering.Inst> insts = new ArrayList<>();
    Map<Instantiation, ChronoLowering.Inst> byInstantiation = new HashMap<>();
    addInstance(this.main, "", insts, byInstantiation);

    Map<List<String>, List<Map.Entry<Integer, String>>> connections = new LinkedHashMap<>();
    for (ChronoLowering.Inst inst : insts) {
      addConnections(inst, connections, byInstantiation);
    }

    List<Map.Entry<String, Long>> capacities = parseCapacities();
    String profile = targetConfig.get(BindingProfileProperty.INSTANCE).toString();

    byte[] sourceBytes = Files.readAllBytes(FileUtil.toPath(resource));
    byte[] sha;
    try {
      sha = MessageDigest.getInstance("SHA-256").digest(sourceBytes);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
    ChronoCsf.Meta meta =
        new ChronoCsf.Meta(sha, "lfc " + LocalStrings.VERSION, CHRONOC_VERSION, "Chrono", 0, 0);
    return ChronoLowering.lower(insts, connections, capacities, profile, meta);
  }

  private void addInstance(
      ReactorInstance ri,
      String path,
      List<ChronoLowering.Inst> insts,
      Map<Instantiation, ChronoLowering.Inst> byInstantiation)
      throws ChronoLowering.SubsetException {
    if (ri.reactorDefinition == null) {
      throw new ChronoLowering.SubsetException(
          "lowering error: reactor instance without a definition");
    }
    if (ri.getWidth() > 1) {
      throw new ChronoLowering.SubsetException(
          "lowering error: banks of reactors are not supported in subset v1 (instance \""
              + path
              + "\")");
    }
    ChronoLowering.Inst inst = new ChronoLowering.Inst();
    inst.idx = insts.size();
    inst.path = path;
    inst.def = ri.reactorDefinition;
    insts.add(inst);
    byInstantiation.put(ri.getDefinition(), inst);

    if (!inst.def.getModes().isEmpty()) {
      throw new ChronoLowering.SubsetException(
          "lowering error: modal reactors are not supported in subset v1 (reactor "
              + inst.def.getName()
              + ")");
    }
    if (!inst.def.getWatchdogs().isEmpty()) {
      throw new ChronoLowering.SubsetException(
          "lowering error: watchdogs are not supported in subset v1 (reactor "
              + inst.def.getName()
              + ")");
    }
    for (var p : inst.def.getParameters()) {
      Integer v = ri.initialIntParameterValue(p);
      if (v == null) {
        throw new ChronoLowering.SubsetException(
            "lowering error: parameter \""
                + p.getName()
                + "\" of reactor "
                + inst.def.getName()
                + " has no integer value (subset v1 supports integer parameters only)");
      }
      inst.params.put(p.getName(), v.longValue());
    }
    for (TimerInstance t : ri.timers) {
      if (t.getDefinition() == null) {
        continue; // Builtin startup/shutdown timers are not declared timers.
      }
      inst.timers.put(t.getDefinition().getName(), t.getPeriod().toNanoSeconds());
    }
    for (var s : inst.def.getStateVars()) {
      inst.states.add(s.getName());
    }
    for (var in : inst.def.getInputs()) {
      if (in.getWidthSpec() != null) {
        throw new ChronoLowering.SubsetException(
            "lowering error: multiports are not supported in subset v1 (port "
                + in.getName()
                + ")");
      }
      inst.ports.put(in.getName(), true);
    }
    for (var out : inst.def.getOutputs()) {
      if (out.getWidthSpec() != null) {
        throw new ChronoLowering.SubsetException(
            "lowering error: multiports are not supported in subset v1 (port "
                + out.getName()
                + ")");
      }
      inst.ports.put(out.getName(), false);
    }
    for (var a : inst.def.getActions()) {
      inst.actions.add(a.getName());
    }
    for (Reaction r : inst.def.getReactions()) {
      inst.reactionAsts.add(r);
      if (r.getCode() == null) {
        throw new ChronoLowering.SubsetException(
            "lowering error: "
                + inst.def.getName()
                + ": reaction without a body (subset v1 requires inlined bodies)");
      }
      var node = NodeModelUtils.findActualNodeFor(r.getCode());
      int baseLine = node != null ? node.getStartLine() : 1;
      try {
        inst.bodies.add(ChronoBody.parseBody(r.getCode().getBody(), baseLine));
      } catch (ChronoBody.BodyException e) {
        throw new ChronoLowering.SubsetException(
            "lowering error: "
                + inst.def.getName()
                + ".body: body error at line "
                + e.line
                + ": "
                + e.getMessage());
      }
    }
    for (ReactorInstance child : ri.children) {
      String childPath = path.isEmpty() ? child.getName() : path + "." + child.getName();
      addInstance(child, childPath, insts, byInstantiation);
    }
  }

  private void addConnections(
      ChronoLowering.Inst inst,
      Map<List<String>, List<Map.Entry<Integer, String>>> connections,
      Map<Instantiation, ChronoLowering.Inst> byInstantiation)
      throws ChronoLowering.SubsetException {
    for (Connection c : inst.def.getConnections()) {
      if (c.isPhysical()) {
        throw new ChronoLowering.SubsetException(
            "lowering error: physical connections (~>) are not supported in subset v1");
      }
      if (c.getDelay() != null) {
        throw new ChronoLowering.SubsetException(
            "lowering error: connection delays (after) are not supported in subset v1");
      }
      if (c.getLeftPorts().size() != c.getRightPorts().size()) {
        throw new ChronoLowering.SubsetException(
            "lowering error: connection with mismatched port counts is not supported in subset v1");
      }
      for (int i = 0; i < c.getLeftPorts().size(); i++) {
        VarRef srcRef = c.getLeftPorts().get(i);
        VarRef dstRef = c.getRightPorts().get(i);
        ChronoLowering.Inst srcInst = resolveRefInstance(inst, srcRef, byInstantiation);
        ChronoLowering.Inst dstInst = resolveRefInstance(inst, dstRef, byInstantiation);
        String srcPort = portName(srcRef);
        String dstPort = portName(dstRef);
        // Validate endpoints exactly as chronoc does: sources are outputs,
        // destinations are inputs, and both ports must be declared.
        Boolean srcIsInput = srcInst.ports.get(srcPort);
        if (srcIsInput == null) {
          throw new ChronoLowering.SubsetException(
              "lowering error: connection: "
                  + displayPath(srcInst)
                  + " has no port \""
                  + srcPort
                  + "\"");
        }
        if (srcIsInput) {
          throw new ChronoLowering.SubsetException(
              "lowering error: connection: "
                  + displayPath(srcInst)
                  + "."
                  + srcPort
                  + " is an input port (sources must be outputs)");
        }
        Boolean dstIsInput = dstInst.ports.get(dstPort);
        if (dstIsInput == null) {
          throw new ChronoLowering.SubsetException(
              "lowering error: connection: "
                  + displayPath(dstInst)
                  + " has no port \""
                  + dstPort
                  + "\"");
        }
        if (!dstIsInput) {
          throw new ChronoLowering.SubsetException(
              "lowering error: connection: "
                  + displayPath(dstInst)
                  + "."
                  + dstPort
                  + " is an output port (destinations must be inputs)");
        }
        connections
            .computeIfAbsent(List.of(dstInst.path, dstPort), k -> new ArrayList<>())
            .add(Map.entry(srcInst.idx, srcPort));
      }
    }
  }

  private static String displayPath(ChronoLowering.Inst inst) {
    return inst.path.isEmpty() ? "main" : inst.path;
  }

  private static String portName(VarRef ref) throws ChronoLowering.SubsetException {
    if (!(ref.getVariable() instanceof Port)) {
      throw new ChronoLowering.SubsetException(
          "lowering error: connection endpoint is not a port: \"" + ref.getVariable().getName() + "\"");
    }
    return ref.getVariable().getName();
  }

  private ChronoLowering.Inst resolveRefInstance(
      ChronoLowering.Inst containing,
      VarRef ref,
      Map<Instantiation, ChronoLowering.Inst> byInstantiation)
      throws ChronoLowering.SubsetException {
    if (ref.getContainer() == null) {
      return containing;
    }
    ChronoLowering.Inst target = byInstantiation.get(ref.getContainer());
    if (target == null) {
      throw new ChronoLowering.SubsetException(
          "lowering error: connection: unknown instance \"" + ref.getContainer().getName() + "\"");
    }
    return target;
  }

  /** Parse the {@code capacities} target property: comma-separated {@code name=units} pairs. */
  private List<Map.Entry<String, Long>> parseCapacities() throws ChronoLowering.SubsetException {
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
        throw new ChronoLowering.SubsetException(
            "lowering error: capacities expects name=units pairs, got \"" + p + "\"");
      }
      String name = p.substring(0, eq).strip();
      long units;
      try {
        units = Long.parseLong(p.substring(eq + 1).strip());
      } catch (NumberFormatException e) {
        throw new ChronoLowering.SubsetException(
            "lowering error: capacities expects unsigned integer units, got \"" + p + "\"");
      }
      if (units < 0 || units > 0xFFFF_FFFFL) {
        throw new ChronoLowering.SubsetException(
            "lowering error: capacity units out of range in \"" + p + "\"");
      }
      out.add(Map.entry(name, units));
    }
    return out;
  }
}
