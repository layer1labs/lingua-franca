package org.lflang.generator.chrono;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer helpers for the canonical Chrono model (see {@link ChronoGenerator} for the
 * schema). Hand-rolled to avoid adding a JSON dependency to the LF compiler for one document type.
 */
final class ChronoModelJson {

  private ChronoModelJson() {}

  static String esc(String s) {
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

  static String str(String s) {
    return "\"" + esc(s) + "\"";
  }

  static String strList(List<String> xs) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) {
        b.append(", ");
      }
      b.append(str(xs.get(i)));
    }
    return b.append("]").toString();
  }

  static String pairs(List<Map.Entry<String, Long>> xs) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) {
        b.append(", ");
      }
      b.append("[")
          .append(str(xs.get(i).getKey()))
          .append(", ")
          .append(xs.get(i).getValue())
          .append("]");
    }
    return b.append("]").toString();
  }
}
