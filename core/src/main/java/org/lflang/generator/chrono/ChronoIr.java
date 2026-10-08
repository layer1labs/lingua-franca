package org.lflang.generator.chrono;

import java.util.List;
import java.util.Map;

/**
 * The {@code csf-ir} debug form: a JSON rendering of the exact {@link ChronoCsf.Model} the binary
 * writer consumes (the analogue of chronoc's {@code --emit-ir} CHB-JSON form, renamed with the
 * format). It carries the same information as the {@code .csf} artifact — capacities, operations,
 * schedule, bindings, and provenance metadata including {@code lf_target} — so the JSON can be
 * diffed against the binary output or inspected when a compile surprises. It is a debug form
 * only: engines never read it.
 */
public final class ChronoIr {

  private ChronoIr() {}

  private static String onRefuseStr(int code) {
    return switch (code) {
      case ChronoCsf.MUST_ADMIT -> "must-admit";
      case ChronoCsf.RETRY -> "retry";
      case ChronoCsf.DROP -> "drop";
      default -> throw new AssertionError("bad on_refuse code " + code);
    };
  }

  private static String esc(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.toString();
  }

  private static String hex(byte[] bytes) {
    StringBuilder b = new StringBuilder();
    for (byte x : bytes) {
      b.append(String.format("%02x", x));
    }
    return b.toString();
  }

  /** Pretty-printed csf-ir JSON for the given model. */
  public static String toJson(ChronoCsf.Model m) {
    StringBuilder b = new StringBuilder();
    b.append("{\n");
    b.append("  \"format\": \"csf-ir\",\n");
    b.append("  \"version\": 1,\n");
    b.append("  \"capacities\": [");
    for (int i = 0; i < m.capacities.size(); i++) {
      Map.Entry<String, Long> c = m.capacities.get(i);
      if (i > 0) b.append(",");
      b.append("\n    {\"name\": \"").append(esc(c.getKey())).append("\", \"units\": ").append(c.getValue()).append("}");
    }
    b.append(m.capacities.isEmpty() ? "],\n" : "\n  ],\n");
    b.append("  \"operations\": [");
    for (int i = 0; i < m.operations.size(); i++) {
      ChronoCsf.Op op = m.operations.get(i);
      if (i > 0) b.append(",");
      b.append("\n    {\"id\": \"").append(esc(op.id())).append("\", \"effector\": \"").append(esc(op.effector())).append("\", \"on_refuse\": \"").append(onRefuseStr(op.onRefuse())).append("\", \"demands\": [");
      for (int j = 0; j < op.demands().size(); j++) {
        ChronoCsf.Demand d = op.demands().get(j);
        if (j > 0) b.append(", ");
        b.append("{\"resource\": \"").append(esc(d.resource())).append("\", \"units\": ").append(d.units()).append("}");
      }
      b.append("], \"deps\": ").append(ints(op.deps())).append("}");
    }
    b.append(m.operations.isEmpty() ? "],\n" : "\n  ],\n");
    b.append("  \"schedule\": [");
    for (int i = 0; i < m.schedule.size(); i++) {
      ChronoCsf.Step s = m.schedule.get(i);
      if (i > 0) b.append(",");
      b.append("\n    {\"step\": ").append(s.stepNo()).append(", \"ops\": ").append(ints(s.ops())).append("}");
    }
    b.append(m.schedule.isEmpty() ? "],\n" : "\n  ],\n");
    b.append("  \"bindings\": [");
    for (int i = 0; i < m.bindings.size(); i++) {
      Map.Entry<String, Integer> bind = m.bindings.get(i);
      if (i > 0) b.append(",");
      b.append("\n    {\"lf_path\": \"").append(esc(bind.getKey())).append("\", \"op\": ").append(bind.getValue()).append("}");
    }
    b.append(m.bindings.isEmpty() ? "],\n" : "\n  ],\n");
    b.append("  \"meta\": {\n");
    b.append("    \"lf_sha256\": \"").append(hex(m.meta.lfSha256())).append("\",\n");
    b.append("    \"lfc_version\": \"").append(esc(m.meta.lfcVersion())).append("\",\n");
    b.append("    \"chronoc_version\": \"").append(esc(m.meta.chronocVersion())).append("\",\n");
    b.append("    \"lf_target\": \"").append(esc(m.meta.lfTarget())).append("\",\n");
    b.append("    \"target_steps\": ").append(m.meta.targetSteps()).append(",\n");
    b.append("    \"step_ns\": ").append(m.meta.stepNs()).append("\n");
    b.append("  }\n");
    b.append("}\n");
    return b.toString();
  }

  private static String ints(List<Integer> xs) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) b.append(", ");
      b.append(xs.get(i));
    }
    return b.append("]").toString();
  }
}
