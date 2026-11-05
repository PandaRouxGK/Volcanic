package dev.vitrail;

import java.nio.file.Path;

/**
 * Host surface the pack front end needs from whatever drives it.
 * <p>
 * Carried over from Vitrail, where this class is the mod's own entry point. Here it is the seam
 * between the pack pipeline and the engine that hosts it: the logger is the only thing the
 * front end (pack/, uniform/, glsl/) ever asks for, so an engine routes those lines wherever it
 * wants and nothing in those packages has to know which engine it is running under.
 */
public final class Vitrail {

	/** What the front end logs through, and nothing else. */
	public interface Logger {
		void info(String format, Object... arguments);

		void warn(String format, Object... arguments);

		void error(String format, Object... arguments);

		void debug(String format, Object... arguments);

		boolean isDebugEnabled();
	}

	private static volatile Logger logger = new SystemLogger();

	private Vitrail() {
	}

	public static Logger logger() {
		return logger;
	}

	public static void setLogger(Logger replacement) {
		logger = replacement == null ? new SystemLogger() : replacement;
	}

	private static final class SystemLogger implements Logger {
		@Override
		public void info(String format, Object... arguments) {
			System.out.println("[vitrail] " + format(format, arguments));
		}

		@Override
		public void warn(String format, Object... arguments) {
			System.out.println("[vitrail] " + format(format, arguments));
		}

		@Override
		public void error(String format, Object... arguments) {
			System.err.println("[vitrail] " + format(format, arguments));
		}

		@Override
		public void debug(String format, Object... arguments) {
		}

		@Override
		public boolean isDebugEnabled() {
			return false;
		}

		private static String format(String format, Object... arguments) {
			StringBuilder out = new StringBuilder(format.length() + 16);
			int argument = 0;
			int at = 0;

			while (at < format.length()) {
				int brace = format.indexOf("{}", at);
				if (brace < 0 || argument >= arguments.length) {
					out.append(format, at, format.length());
					break;
				}
				out.append(format, at, brace);
				out.append(arguments[argument++]);
				at = brace + 2;
			}
			while (argument < arguments.length) {
				out.append(' ').append(arguments[argument++]);
			}
			return out.toString();
		}
	}

	/** Kept for the front end's few path questions; answered by whoever hosts it. */
	public interface Paths {
		Path gameDirectory();
	}
}
