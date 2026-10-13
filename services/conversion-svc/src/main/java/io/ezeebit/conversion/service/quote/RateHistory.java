package io.ezeebit.conversion.service.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.repo.QuoteRepo;
import org.springframework.stereotype.Component;

@Component
public class RateHistory {

	private final QuoteRepo quotes;
	private final ConversionProperties properties;
	private final Clock clock;

	public RateHistory(final QuoteRepo quotes, final ConversionProperties properties, final Clock clock) {
		this.quotes = quotes;
		this.properties = properties;
		this.clock = clock;
	}

	// The instant is the quote's "now" too: the rate is checked and the quote expires against the same clock read.
	public Snapshot read(final String sell, final String buy) {
		Instant now = clock.instant();
		return new Snapshot(now, quotes.recentRate(sell, buy, now.minus(properties.rates().jumpWindow())));
	}

	public record Snapshot(Instant now, Optional<BigDecimal> recent) {
	}

}
