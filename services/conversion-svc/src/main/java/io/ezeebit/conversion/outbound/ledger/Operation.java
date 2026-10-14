package io.ezeebit.conversion.outbound.ledger;

// One Resilience4j instance per ledger operation, so a failing Void cannot open the breaker in front of Post.
public enum Operation {

	POST("ledgerPost"), FIND("ledgerFind"), VOID("ledgerVoid");

	private final String instance;

	Operation(final String instance) {
		this.instance = instance;
	}

	public String instance() {
		return instance;
	}

}
