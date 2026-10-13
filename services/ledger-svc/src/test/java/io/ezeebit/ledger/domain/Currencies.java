package io.ezeebit.ledger.domain;

final class Currencies {

	static final Currency ZAR = new Currency(new CurrencyCode("ZAR"), CurrencyKind.FIAT, 2);
	static final Currency USDT = new Currency(new CurrencyCode("USDT"), CurrencyKind.STABLECOIN, 6);

	private Currencies() {
	}

}
