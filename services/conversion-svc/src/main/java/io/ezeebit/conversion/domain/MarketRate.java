package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.time.Instant;

// What the exchange-rate service said: units of quote per one unit of base, as of timestamp.
public record MarketRate(String base, String quote, BigDecimal rate, Instant timestamp) {
}
