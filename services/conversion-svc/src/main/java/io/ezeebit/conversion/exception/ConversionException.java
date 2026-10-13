package io.ezeebit.conversion.exception;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

// Carries a code, message arguments and extra problem properties, never user-facing text.
public class ConversionException extends RuntimeException {

	private final ErrorCode code;
	private final String field;
	private final Object[] args;
	private final Map<String, Object> properties = new LinkedHashMap<>();

	public ConversionException(final ErrorCode code, final String field, final Object... args) {
		super("%s field=%s args=%s".formatted(code, field, Arrays.toString(args)));
		this.code = code;
		this.field = field;
		this.args = args.clone();
	}

	public ConversionException with(final String name, final Object value) {
		properties.put(name, value);
		return this;
	}

	public ErrorCode code() {
		return code;
	}

	public String field() {
		return field;
	}

	public Object[] args() {
		return args.clone();
	}

	public Map<String, Object> properties() {
		return Map.copyOf(properties);
	}

}
