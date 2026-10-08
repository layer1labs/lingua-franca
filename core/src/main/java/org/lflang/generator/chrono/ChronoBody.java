package org.lflang.generator.chrono;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Chrono reaction bodies: line-oriented, indentation-aware, restricted to the
 * ChronoHive subset (spec 002, REQ-102). This is a direct Java port of the reference parser in
 * chronoc ({@code body.rs}); it parses only the raw host-code text of a reaction body, exactly as
 * every LF target receives it. All LF program structure (reactors, timers, ports, connections,
 * instantiations) comes from the real LF AST / instance graph in {@link ChronoGenerator}, never
 * from this parser.
 *
 * <p>Accepted statements:
 *
 * <pre>
 *   train_step() | admit_checkpoint(mb=&lt;expr&gt;) | prefetch(depth=&lt;expr&gt;)
 *   x = &lt;expr&gt; | x = x + 1 | x += 1
 *   port.set(&lt;opaque&gt;)
 *   request_stop()
 *   if &lt;var&gt; % &lt;expr&gt; == 0: | if &lt;var&gt; &gt;= &lt;expr&gt;:
 * </pre>
 *
 * Anything else is a hard error naming the offending line.
 */
public final class ChronoBody {

  private ChronoBody() {}

  /** An error in a reaction body, carrying the 1-based source line. */
  public static final class BodyException extends Exception {
    public final int line;

    public BodyException(int line, String msg) {
      super(msg);
      this.line = line;
    }
  }

  /** Body expressions: integer literal, parameter reference, or opaque raw text. */
  public sealed interface Expr permits IntExpr, RefExpr, RawExpr {}

  public record IntExpr(long value) implements Expr {}

  public record RefExpr(String name) implements Expr {}

  public record RawExpr(String text) implements Expr {}

  /** Guard conditions: {@code c % k == 0} or {@code c >= bound}. */
  public sealed interface Cond permits ModZero, Ge {}

  public record ModZero(String var, Expr modulus) implements Cond {}

  public record Ge(String var, Expr bound) implements Cond {}

  /** Body statements. */
  public sealed interface Stmt
      permits Intrinsic, Incr, Assign, PortSet, RequestStop, If {}

  public record Intrinsic(String name, List<java.util.Map.Entry<String, Expr>> args, int line)
      implements Stmt {}

  public record Incr(String var, int line) implements Stmt {}

  public record Assign(String var, Expr expr, int line) implements Stmt {}

  public record PortSet(String port, int line) implements Stmt {}

  public record RequestStop(int line) implements Stmt {}

  public record If(Cond cond, List<Stmt> body, int line) implements Stmt {}

  private static int indentOf(String line) {
    int n = 0;
    while (n < line.length() && line.charAt(n) == ' ') {
      n++;
    }
    return n;
  }

  private static Expr parseExpr(String text, int line) throws BodyException {
    String t = text.strip();
    if (t.isEmpty()) {
      throw new BodyException(line, "empty expression");
    }
    try {
      return new IntExpr(Long.parseLong(t));
    } catch (NumberFormatException ignored) {
      // fall through
    }
    if (t.startsWith("\"") || t.startsWith("{") || t.startsWith("'")) {
      return new RawExpr(t);
    }
    boolean ident =
        t.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')
            && !t.isEmpty()
            && (Character.isLetter(t.charAt(0)) || t.charAt(0) == '_');
    if (ident) {
      return new RefExpr(t);
    }
    throw new BodyException(line, "unsupported expression \"" + t + "\"");
  }

  /** Split {@code name(args)} into {name, argsText}; handles nested parens. */
  private static String[] splitCall(String text, int line) throws BodyException {
    int open = text.indexOf('(');
    if (open < 0) {
      throw new BodyException(line, "expected call, got \"" + text + "\"");
    }
    String name = text.substring(0, open).strip();
    int depth = 0;
    int close = -1;
    for (int i = open; i < text.length(); i++) {
      char b = text.charAt(i);
      if (b == '(') {
        depth++;
      } else if (b == ')') {
        depth--;
        if (depth == 0) {
          close = i;
          break;
        }
      }
    }
    if (close < 0) {
      throw new BodyException(line, "unbalanced parens in \"" + text + "\"");
    }
    if (!text.substring(close + 1).strip().isEmpty()) {
      throw new BodyException(line, "trailing text after call in \"" + text + "\"");
    }
    return new String[] {name, text.substring(open + 1, close).strip()};
  }

  private static List<java.util.Map.Entry<String, Expr>> parseIntrinsicArgs(
      String name, String argsText, int line) throws BodyException {
    // Only keyword args of the form k=v, comma-separated at depth 0.
    List<java.util.Map.Entry<String, Expr>> out = new ArrayList<>();
    if (argsText.isEmpty()) {
      return out;
    }
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < argsText.length(); i++) {
      char b = argsText.charAt(i);
      switch (b) {
        case '(', '{', '[' -> depth++;
        case ')', '}', ']' -> depth--;
        case ',' -> {
          if (depth == 0) {
            parts.add(argsText.substring(start, i).strip());
            start = i + 1;
          }
        }
        default -> {}
      }
    }
    parts.add(argsText.substring(start).strip());
    for (String part : parts) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        throw new BodyException(
            line, "intrinsic " + name + " takes keyword args only, got \"" + part + "\"");
      }
      String key = part.substring(0, eq).strip();
      Expr val = parseExpr(part.substring(eq + 1).strip(), line);
      out.add(java.util.Map.entry(key, val));
    }
    return out;
  }

  private static Cond parseCond(String text, int line) throws BodyException {
    String t = text.strip();
    while (t.endsWith(":")) {
      t = t.substring(0, t.length() - 1).strip();
    }
    int pct = t.indexOf('%');
    if (pct >= 0) {
      String var = t.substring(0, pct).strip();
      String rest = t.substring(pct + 1).strip();
      int eq = rest.indexOf("==");
      if (eq < 0) {
        throw new BodyException(
            line, "only '== 0' modulo guards are supported, got \"" + text + "\"");
      }
      Expr modulus = parseExpr(rest.substring(0, eq).strip(), line);
      String rhs = rest.substring(eq + 2).strip();
      if (!rhs.equals("0")) {
        throw new BodyException(
            line, "only '== 0' modulo guards are supported, got \"" + text + "\"");
      }
      if (var.isEmpty() || !var.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_')) {
        throw new BodyException(line, "bad guard variable in \"" + text + "\"");
      }
      return new ModZero(var, modulus);
    }
    int ge = t.indexOf(">=");
    if (ge >= 0) {
      String var = t.substring(0, ge).strip();
      Expr bound = parseExpr(t.substring(ge + 2).strip(), line);
      if (var.isEmpty()) {
        throw new BodyException(line, "bad guard in \"" + text + "\"");
      }
      return new Ge(var, bound);
    }
    throw new BodyException(
        line,
        "unsupported guard \""
            + text
            + "\"; only '<v> % <k> == 0' and '<v> >= <b>' are supported");
  }

  private static Stmt parseStmtLine(String text, int line) throws BodyException {
    String t = text.strip();
    if (t.startsWith("if ")) {
      throw new BodyException(line, "nested parse: 'if' handled by block parser");
    }
    if (t.equals("request_stop()")) {
      return new RequestStop(line);
    }
    // port.set(...)
    if (t.contains(".set(")) {
      String[] na = splitCall(t, line);
      String[] segs = na[0].split("\\.", -1);
      if (segs.length != 2 || !segs[1].equals("set") || segs[0].isEmpty()) {
        throw new BodyException(line, "unsupported call \"" + t + "\"");
      }
      return new PortSet(segs[0], line);
    }
    // bare call: intrinsic (checked before '=' so keyword args like mb=mb
    // are not mistaken for assignments)
    if (t.endsWith(")") && t.contains("(")) {
      String[] na = splitCall(t, line);
      if (na[0].contains(".") || na[0].contains(" ")) {
        throw new BodyException(line, "unsupported call \"" + t + "\"");
      }
      return new Intrinsic(na[0], parseIntrinsicArgs(na[0], na[1], line), line);
    }
    // assignment (but not ==)
    int idx = t.indexOf('=');
    if (idx >= 0) {
      if (t.substring(idx + 1).startsWith("=")) {
        throw new BodyException(line, "bare comparison is not a statement: \"" + t + "\"");
      }
      String var = t.substring(0, idx).strip();
      // x += 1
      if (var.endsWith("+")) {
        return new Incr(var.substring(0, var.length() - 1).strip(), line);
      }
      String rhs = t.substring(idx + 1).strip();
      // x = x + 1
      if (rhs.equals(var + " + 1")) {
        return new Incr(var, line);
      }
      return new Assign(var, parseExpr(rhs, line), line);
    }
    throw new BodyException(line, "unsupported statement \"" + t + "\"");
  }

  private record Line(int indent, String text, int lineNo) {}

  /**
   * Parse a reaction body. {@code baseLine} is the 1-based source line of the {@code {=}} token.
   * The common leading indentation is stripped first (LF bodies are indented inside the reaction
   * braces).
   */
  public static List<Stmt> parseBody(String text, int baseLine) throws BodyException {
    List<Line> raw = new ArrayList<>();
    String[] split = text.split("\n", -1);
    for (int off = 0; off < split.length; off++) {
      String stripped = split[off].strip();
      if (stripped.isEmpty() || stripped.startsWith("#")) {
        continue;
      }
      raw.add(new Line(indentOf(split[off]), stripped, baseLine + off + 1));
    }
    int minIndent = raw.stream().mapToInt(Line::indent).min().orElse(0);
    List<Line> lines = new ArrayList<>();
    for (Line l : raw) {
      lines.add(new Line(l.indent() - minIndent, l.text(), l.lineNo()));
    }
    int[] pos = {0};
    return parseBlock(lines, pos, 0, true);
  }

  private static List<Stmt> parseBlock(List<Line> lines, int[] pos, int parentIndent, boolean isRoot)
      throws BodyException {
    List<Stmt> out = new ArrayList<>();
    while (pos[0] < lines.size()) {
      Line cur = lines.get(pos[0]);
      if (!isRoot && cur.indent() <= parentIndent) {
        break;
      }
      if (isRoot && cur.indent() > 0) {
        throw new BodyException(cur.lineNo(), "unexpected indent at top level");
      }
      if (cur.text().startsWith("if ")) {
        Cond cond = parseCond(cur.text().substring(3).strip(), cur.lineNo());
        pos[0]++;
        List<Stmt> body = parseBlock(lines, pos, cur.indent(), false);
        if (body.isEmpty()) {
          throw new BodyException(cur.lineNo(), "'if' with empty body");
        }
        out.add(new If(cond, body, cur.lineNo()));
        continue;
      }
      out.add(parseStmtLine(cur.text(), cur.lineNo()));
      pos[0]++;
    }
    return out;
  }
}
