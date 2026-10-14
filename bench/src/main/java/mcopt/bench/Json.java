package mcopt.bench;

import java.util.Collection;
import java.util.Map;

/** Minimal pretty JSON writer for the bench report (strings, numbers, booleans, maps, collections). */
final class Json {
	private Json() {
	}

	static String write(Object o) {
		StringBuilder b = new StringBuilder();
		write(o, b, 0);
		return b.toString();
	}

	private static void write(Object o, StringBuilder b, int depth) {
		if (o == null) {
			b.append("null");
		} else if (o instanceof Number || o instanceof Boolean) {
			b.append(o);
		} else if (o instanceof Map<?, ?> m) {
			b.append('{');
			int i = 0;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				b.append(i++ == 0 ? "" : ",").append('\n').append("  ".repeat(depth + 1));
				string(String.valueOf(e.getKey()), b);
				b.append(": ");
				write(e.getValue(), b, depth + 1);
			}
			b.append(m.isEmpty() ? "" : "\n" + "  ".repeat(depth)).append('}');
		} else if (o instanceof Collection<?> c) {
			b.append('[');
			int i = 0;
			for (Object x : c) {
				b.append(i++ == 0 ? "" : ",").append('\n').append("  ".repeat(depth + 1));
				write(x, b, depth + 1);
			}
			b.append(c.isEmpty() ? "" : "\n" + "  ".repeat(depth)).append(']');
		} else {
			string(String.valueOf(o), b);
		}
	}

	private static void string(String s, StringBuilder b) {
		b.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
				}
			}
		}
		b.append('"');
	}
}
