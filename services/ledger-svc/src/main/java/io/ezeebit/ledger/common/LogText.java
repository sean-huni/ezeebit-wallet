package io.ezeebit.ledger.common;

import java.util.regex.Pattern;

// Log arguments that can carry caller or upstream text go through here, so one line stays one event.
public final class LogText {

	private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\x{80}-\\x{9F}\\x{2028}\\x{2029}]");

	private static final int MAX_CAUSES = 16;

	private LogText() {
	}

	public static String oneLine(final Object value) {
		return CONTROL.matcher(String.valueOf(value)).replaceAll("_");
	}

	// A copy of the cause chain for logging: same types and stack frames, messages through oneLine.
	public static Throwable sanitised(final Throwable ex) {
		return sanitised(ex, 0);
	}

	private static Throwable sanitised(final Throwable ex, final int depth) {
		Throwable cause = ex.getCause();
		String message = ex.getMessage();
		String text = message == null ? ex.getClass().getName() : ex.getClass().getName() + ": " + oneLine(message);
		Sanitised copy = new Sanitised(text, cause == null || depth >= MAX_CAUSES ? null : sanitised(cause, depth + 1));
		copy.setStackTrace(ex.getStackTrace());
		return copy;
	}

	private static final class Sanitised extends Throwable {

		private static final long serialVersionUID = 1L;

		Sanitised(final String text, final Throwable cause) {
			super(text, cause, false, true);
		}

		@Override
		public String toString() {
			return getMessage();
		}

	}

}
