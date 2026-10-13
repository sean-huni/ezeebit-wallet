package io.ezeebit.conversion.domain;

import static io.ezeebit.conversion.domain.Currencies.USDT;
import static io.ezeebit.conversion.domain.Currencies.ZAR;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class QuoteTest {

	private static final Instant EXPIRES = Instant.parse("2026-10-13T10:00:15Z");

	private final Quote quote = new Quote(UUID.randomUUID(), UUID.randomUUID(), Money.parse("10", USDT),
			Money.parse("183.39", ZAR), new BigDecimal("18.4321"), new BigDecimal("18.3399395"), 50,
			Instant.parse("2026-10-13T09:59:58Z"), EXPIRES);

	@Test
	void aQuoteIsUsableUntilJustBeforeItExpires() {
		assertThat(quote.expired(EXPIRES.minusNanos(1))).isFalse();
	}

	@Test
	void aQuoteIsExpiredAtItsExpiryInstant() {
		assertThat(quote.expired(EXPIRES)).isTrue();
		assertThat(quote.expired(EXPIRES.plusSeconds(1))).isTrue();
	}

	@Test
	void thePostingDeadlineIsTheExpiryPlusTheGrace() {
		assertThat(quote.postingDeadline(Duration.ofSeconds(30))).isEqualTo(EXPIRES.plusSeconds(30));
	}

}
