package io.ezeebit.conversion.config.properties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Served by config-svc (served/conversion-svc.yml): pricing policy changes without a release.
@ConfigurationProperties("conversion")
public record ConversionProperties(Duration quoteTtl, Duration postingGrace, Duration maxTransactionTime, Rates rates,
		Ledger ledger, Map<String, Integer> currencies, Map<String, Pair> pairs) {

	public record Rates(String url, Duration connectTimeout, Duration readTimeout, Duration maxAge,
			BigDecimal maxJumpPercent, Duration jumpWindow) {
	}

	public record Ledger(String url, Duration connectTimeout, Duration readTimeout) {
	}

	public record Pair(BigDecimal min, BigDecimal max, int spreadBps) {
	}

}
