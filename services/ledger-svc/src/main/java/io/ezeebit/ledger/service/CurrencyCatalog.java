package io.ezeebit.ledger.service;

import java.util.Map;

import io.ezeebit.ledger.domain.Currency;

public interface CurrencyCatalog {

	Currency require(String code);

	Map<String, Currency> all();

}
