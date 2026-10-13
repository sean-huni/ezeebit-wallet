package io.ezeebit.ledger.common;

import java.util.regex.Pattern;

// Log arguments that can carry caller or upstream text go through here, so one line stays one event.
public final class LogText {

	private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

	private LogText() {
	}

	public static String oneLine(final Object value) {
		return CONTROL.matcher(String.valueOf(value)).replaceAll("_");
	}

}
