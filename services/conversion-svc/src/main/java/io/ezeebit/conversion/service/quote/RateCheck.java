package io.ezeebit.conversion.service.quote;

import java.time.Instant;

import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.PairPolicy;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.service.port.RateSource;
import io.ezeebit.conversion.service.quote.RateHistory.Snapshot;
import org.springframework.stereotype.Component;

@Component
public class RateCheck {

	private final RateSource rates;
	private final RateValidator validator;
	private final RateHistory history;

	public RateCheck(final RateSource rates, final RateValidator validator, final RateHistory history) {
		this.rates = rates;
		this.validator = validator;
		this.history = history;
	}

	public CheckedRate fetch(final Currency sell, final Currency buy, final PairPolicy policy) {
		MarketRate rate = rates.fetch(sell.code(), buy.code());
		Snapshot snapshot = history.read(sell.code(), buy.code());
		validator.check(rate, policy, snapshot.recent(), snapshot.now());
		return new CheckedRate(rate, snapshot.now());
	}

	public record CheckedRate(MarketRate rate, Instant checkedAt) {
	}

}
