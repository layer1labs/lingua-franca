package org.lflang.generator.chrono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The Constraint Specification Format (CSF1) model and its binary writer: a byte-compatible Java port of chronoc's {@code
 * blob.rs} (spec 002, REQ-106 as amended by REQ-113). This writer is the shared contract between
 * the Chrono target and every engine: the format is specified normatively in spec 002, this class
 * and the Rust writer must emit it identically for identical models, and engines (ChronoHive
 * software, ChronoFabric hardware) consume it without ever reading the {@code .lf} source.
 *
 * <p>Layout (all integers little-endian):
 *
 * <pre>
 *   magic "CSF1" (4) | version u16 (=1) | flags u16 (=0) | step_ns u64
 *   n_strings u32, then for each: len u32 + UTF-8 bytes
 *   n_capacities u32, then for each: name_idx u32 + units u32
 *   n_ops u32, then for each:
 *     id_idx u32 | effector_idx u32 | on_refuse u8 | n_demands u16
 *     then per demand: res_idx u32 + units u32
 *     n_deps u16, then dep op indices u32 each
 *   n_steps u32, then for each: step_no u32 | n_ops u16 | op indices u32 each
 *   n_bindings u32, then for each: lf_path_idx u32 | op_idx u32
 *   meta: sha256 (32) | lfc_ver_idx u32 | chronoc_ver_idx u32
 *         | lf_target_idx u32 (REQ-113) | target_steps u32
 *   crc32 u32 (IEEE, over every byte before the trailer)
 * </pre>
 *
 * The format is a single v1 (alpha; it never went into production, so there is no legacy variant
 * and no compatibility branch). {@code lf_target} records the LF target declared in the source —
 * for blobs emitted by this generator, always {@code "Chrono"}.
 */
public final class ChronoCsf {

  private ChronoCsf() {}

  public static final String MAGIC = "CSF1";
  public static final int VERSION = 1;

  /** on_refuse codes (REQ-106): 0 = must-admit, 1 = retry, 2 = drop. */
  public static final int MUST_ADMIT = 0;

  public static final int RETRY = 1;
  public static final int DROP = 2;

  public record Demand(String resource, long units) {}

  public record Op(
      String id,
      String effector,
      int onRefuse,
      List<Demand> demands,
      List<Integer> deps,
      int order) {}

  public record Step(int stepNo, List<Integer> ops) {}

  public record Meta(
      byte[] lfSha256,
      String lfcVersion,
      String chronocVersion,
      String lfTarget,
      int targetSteps,
      long stepNs) {}

  /** The complete CSF model the writer consumes (the Java analogue of chronoc's blob model). */
  public static final class Model {
    public final List<Map.Entry<String, Long>> capacities = new ArrayList<>();
    public final List<Op> operations = new ArrayList<>();
    public final List<Step> schedule = new ArrayList<>();
    public final List<Map.Entry<String, Integer>> bindings = new ArrayList<>();
    public Meta meta;
  }

  private static final class Interner {
    final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> idxOf = new HashMap<>();

    int intern(String s) {
      Integer i = idxOf.get(s);
      if (i != null) {
        return i;
      }
      int idx = strings.size();
      strings.add(s);
      idxOf.put(s, idx);
      return idx;
    }
  }

  private record OpRow(
      int id, int eff, int onRefuse, List<long[]> demands, List<Integer> deps) {}

  /** Serialize the model exactly as chronoc's {@code write_blob} does. */
  public static byte[] write(Model m) {
    Interner in = new Interner();
    List<OpRow> opRows = new ArrayList<>();
    for (Op op : m.operations) {
      int id = in.intern(op.id());
      int eff = in.intern(op.effector());
      List<long[]> demands = new ArrayList<>();
      for (Demand d : op.demands()) {
        demands.add(new long[] {in.intern(d.resource()), d.units()});
      }
      opRows.add(new OpRow(id, eff, op.onRefuse(), demands, op.deps()));
    }
    List<long[]> capRows = new ArrayList<>();
    for (Map.Entry<String, Long> c : m.capacities) {
      capRows.add(new long[] {in.intern(c.getKey()), c.getValue()});
    }
    List<long[]> bindRows = new ArrayList<>();
    for (Map.Entry<String, Integer> b : m.bindings) {
      bindRows.add(new long[] {in.intern(b.getKey()), b.getValue()});
    }
    int lfcVer = in.intern(m.meta.lfcVersion());
    int chronocVer = in.intern(m.meta.chronocVersion());
    int lfTarget = in.intern(m.meta.lfTarget());

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.writeBytes(MAGIC.getBytes(StandardCharsets.US_ASCII));
    w16(buf, VERSION);
    w16(buf, 0); // flags
    w64(buf, m.meta.stepNs());

    w32(buf, in.strings.size());
    for (String s : in.strings) {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      w32(buf, b.length);
      buf.writeBytes(b);
    }

    w32(buf, capRows.size());
    for (long[] r : capRows) {
      w32(buf, r[0]);
      w32(buf, r[1]);
    }

    w32(buf, opRows.size());
    for (OpRow r : opRows) {
      w32(buf, r.id());
      w32(buf, r.eff());
      buf.write(r.onRefuse());
      w16(buf, r.demands().size());
      for (long[] d : r.demands()) {
        w32(buf, d[0]);
        w32(buf, d[1]);
      }
      w16(buf, r.deps().size());
      for (int d : r.deps()) {
        w32(buf, d);
      }
    }

    w32(buf, m.schedule.size());
    for (Step step : m.schedule) {
      w32(buf, step.stepNo());
      w16(buf, step.ops().size());
      for (int o : step.ops()) {
        w32(buf, o);
      }
    }

    w32(buf, bindRows.size());
    for (long[] r : bindRows) {
      w32(buf, r[0]);
      w32(buf, r[1]);
    }

    buf.writeBytes(m.meta.lfSha256());
    w32(buf, lfcVer);
    w32(buf, chronocVer);
    w32(buf, lfTarget);
    w32(buf, m.meta.targetSteps());

    byte[] body = buf.toByteArray();
    CRC32 crc = new CRC32();
    crc.update(body);
    ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 4);
    out.writeBytes(body);
    w32(out, crc.getValue());
    return out.toByteArray();
  }

  private static void w16(ByteArrayOutputStream buf, long v) {
    buf.write((int) (v & 0xFF));
    buf.write((int) ((v >> 8) & 0xFF));
  }

  private static void w32(ByteArrayOutputStream buf, long v) {
    buf.write((int) (v & 0xFF));
    buf.write((int) ((v >> 8) & 0xFF));
    buf.write((int) ((v >> 16) & 0xFF));
    buf.write((int) ((v >> 24) & 0xFF));
  }

  private static void w64(ByteArrayOutputStream buf, long v) {
    for (int i = 0; i < 8; i++) {
      buf.write((int) ((v >> (8 * i)) & 0xFF));
    }
  }
}
