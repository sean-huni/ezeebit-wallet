package io.ezeebit.ledger.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.ezeebit.ledger.data.repo.CurrencyRepo;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.CurrencyCode;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.mapper.CurrencyMapper;
import org.springframework.stereotype.Service;

// Read from the database on every call: no per-replica copy of reference data to go stale.
@Service
public class CurrencyCatalog {

	private final CurrencyRepo currencies;
	private final CurrencyMapper mapper;

	public CurrencyCatalog(final CurrencyRepo currencies, final CurrencyMapper mapper) {
		this.currencies = currencies;
		this.mapper = mapper;
	}

	public Currency require(final String code) {
		CurrencyCode valid = new CurrencyCode(code);
		return currencies.findById(valid.value())
				.map(mapper::toDomain)
				.orElseThrow(() -> new LedgerException(ErrorCode.UNKNOWN_CURRENCY, "currency", code));
	}

	public Map<String, Currency> all() {
		return currencies.findAll().stream()
				.map(mapper::toDomain)
				.collect(Collectors.toMap(currency -> currency.code().value(), Function.identity()));
	}

}
