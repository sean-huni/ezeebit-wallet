package io.ezeebit.conversion.service;

import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.PairPolicy;

public interface CurrencyCatalog {

	Currency require(String code, String field);

	boolean isKnown(String code);

	PairPolicy policy(Currency sell, Currency buy);

}
