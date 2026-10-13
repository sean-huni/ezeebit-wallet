package io.ezeebit.ledger.exception;

import java.util.Arrays;

// Carries a code and message arguments, never user-facing text: the advice resolves the text.
public class LedgerException extends RuntimeException {

	private final ErrorCode code;
	private final String field;
	private final Object[] args;

	public LedgerException(final ErrorCode code, final String field, final Object... args) {
		super("%s field=%s args=%s".formatted(code, field, Arrays.toString(args)));
		this.code = code;
		this.field = field;
		this.args = args.clone();
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

}
