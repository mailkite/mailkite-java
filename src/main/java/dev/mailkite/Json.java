package dev.mailkite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON parser/serializer used by the SDK.
 *
 * <p>Parses into {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Long}/{@code Double}, {@code Boolean}, and {@code null}. Serializes the
 * same shapes back out. Sufficient for the MailKite JSON API; not a general
 * validator.
 */
public final class Json {
  private Json() {}

  // ---- serialize -----------------------------------------------------------
  public static String stringify(Object o) {
    StringBuilder sb = new StringBuilder();
    write(sb, o);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object o) {
    if (o == null) {
      sb.append("null");
    } else if (o instanceof String) {
      writeString(sb, (String) o);
    } else if (o instanceof Boolean) {
      sb.append(o.toString());
    } else if (o instanceof Double || o instanceof Float) {
      double d = ((Number) o).doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d)) sb.append(Long.toString((long) d));
      else sb.append(Double.toString(d));
    } else if (o instanceof Number) {
      sb.append(o.toString());
    } else if (o instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
        if (!first) sb.append(',');
        first = false;
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (o instanceof Iterable) {
      sb.append('[');
      boolean first = true;
      for (Object e : (Iterable<?>) o) {
        if (!first) sb.append(',');
        first = false;
        write(sb, e);
      }
      sb.append(']');
    } else {
      writeString(sb, o.toString());
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    sb.append('"');
  }

  // ---- parse ---------------------------------------------------------------
  public static Object parse(String s) {
    P p = new P(s);
    p.ws();
    Object v = p.value();
    p.ws();
    return v;
  }

  private static final class P {
    final String s;
    int i = 0;

    P(String s) {
      this.s = s;
    }

    void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    char cur() {
      return s.charAt(i);
    }

    Object value() {
      char c = s.charAt(i);
      switch (c) {
        case '{': return obj();
        case '[': return arr();
        case '"': return str();
        case 't': i += 4; return Boolean.TRUE;
        case 'f': i += 5; return Boolean.FALSE;
        case 'n': i += 4; return null;
        default: return num();
      }
    }

    Map<String, Object> obj() {
      Map<String, Object> m = new LinkedHashMap<>();
      i++; // {
      ws();
      if (cur() == '}') { i++; return m; }
      while (true) {
        ws();
        String k = str();
        ws();
        i++; // :
        ws();
        m.put(k, value());
        ws();
        char c = s.charAt(i++);
        if (c == '}') break;
        // else ','
      }
      return m;
    }

    List<Object> arr() {
      List<Object> a = new ArrayList<>();
      i++; // [
      ws();
      if (cur() == ']') { i++; return a; }
      while (true) {
        ws();
        a.add(value());
        ws();
        char c = s.charAt(i++);
        if (c == ']') break;
        // else ','
      }
      return a;
    }

    String str() {
      StringBuilder sb = new StringBuilder();
      i++; // opening "
      while (true) {
        char c = s.charAt(i++);
        if (c == '"') break;
        if (c == '\\') {
          char e = s.charAt(i++);
          switch (e) {
            case '"': sb.append('"'); break;
            case '\\': sb.append('\\'); break;
            case '/': sb.append('/'); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
            default: sb.append(e);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    Object num() {
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
      String n = s.substring(start, i);
      if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
      return Long.parseLong(n);
    }
  }
}
