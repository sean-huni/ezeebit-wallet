package io.ezeebit.ledger.mapper;

import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.CurrencyCode;
import io.ezeebit.ledger.domain.CurrencyKind;

final class Fixtures {

	static final Currency ZAR = new Currency(new CurrencyCode("ZAR"), CurrencyKind.FIAT, 2);
	static final Currency USDT = new Currency(new CurrencyCode("USDT"), CurrencyKind.STABLECOIN, 6);

	private Fixtures() {
	}

}
