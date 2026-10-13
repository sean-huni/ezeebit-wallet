package io.ezeebit.ledger.acceptance;

import java.util.UUID;

final class DepositBody {

	private DepositBody() {
	}

	static String of(final UUID merchantId, final String currency, final String amount, final String reference) {
		return """
				{"merchantId":"%s","currency":"%s","amount":"%s","reference":"%s"}""".formatted(merchantId, currency,
				amount, reference);
	}

}
