package io.ezeebit.conversion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RateValidatorTest {

	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
	private static final PairPolicy USDT_ZAR = new PairPolicy(new BigDecimal("10"), new BigDecimal("30"), 50);

	private final RateValidator validator = new RateValidator(new RateRules(Duration.ofSeconds(10), new BigDecimal("5")));

	@Test
	void aFreshPositiveRateInsideTheBandIsAccepted() {
		assertThatCode(() -> validator.check(rate("18.4321", NOW.minusSeconds(3)), USDT_ZAR, Optional.empty(), NOW))
				.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "0.000000", "-18.4321" })
	void aRateThatIsNotPositiveIsRejected(final String value) {
		assertRejected(rate(value, NOW), Optional.empty(), RateRejection.RATE_NOT_POSITIVE);
	}

	@Test
	void aRateOlderThanTheFreshnessLimitIsRejected() {
		assertRejected(rate("18.4321", NOW.minusSeconds(11)), Optional.empty(), RateRejection.RATE_STALE);
	}

	@Test
	void aRateExactlyAtTheFreshnessLimitIsAccepted() {
		assertThatCode(() -> validator.check(rate("18.4321", NOW.minusSeconds(10)), USDT_ZAR, Optional.empty(), NOW))
				.doesNotThrowAnyException();
	}

	@Test
	void aRateStampedInTheFutureBeyondTheLimitIsRejected() {
		assertRejected(rate("18.4321", NOW.plusSeconds(11)), Optional.empty(), RateRejection.RATE_STALE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "9.9999", "30.0001", "1843.21" })
	void aRateOutsideThePairsBandIsRejected(final String value) {
		assertRejected(rate(value, NOW), Optional.empty(), RateRejection.RATE_OUT_OF_BAND);
	}

	@ParameterizedTest
	@ValueSource(strings = { "10", "30" })
	void theBandIncludesItsEdges(final String value) {
		assertThatCode(() -> validator.check(rate(value, NOW), USDT_ZAR, Optional.empty(), NOW)).doesNotThrowAnyException();
	}

	@Test
	void aJumpBeyondTheLimitFromTheRecentQuoteIsRejected() {
		assertRejected(rate("19.40", NOW), Optional.of(new BigDecimal("18.4321")), RateRejection.RATE_JUMP);
		assertRejected(rate("17.50", NOW), Optional.of(new BigDecimal("18.4321")), RateRejection.RATE_JUMP);
	}

	@Test
	void aMoveWithinTheLimitFromTheRecentQuoteIsAccepted() {
		assertThatCode(() -> validator.check(rate("19.35", NOW), USDT_ZAR, Optional.of(new BigDecimal("18.4321")), NOW))
				.doesNotThrowAnyException();
	}

	@Test
	void withoutARecentQuoteOnlyFreshnessAndTheBandApply() {
		assertThatCode(() -> validator.check(rate("29.00", NOW), USDT_ZAR, Optional.empty(), NOW))
				.doesNotThrowAnyException();
	}

	private void assertRejected(final MarketRate rate, final Optional<BigDecimal> recent, final RateRejection reason) {
		assertThatThrownBy(() -> validator.check(rate, USDT_ZAR, recent, NOW))
				.isInstanceOf(ConversionException.class)
				.satisfies(ex -> {
					assertThat(((ConversionException) ex).code()).isEqualTo(ErrorCode.RATE_REJECTED);
					assertThat(((ConversionException) ex).properties()).containsEntry("reason", reason.name());
				});
	}

	private static MarketRate rate(final String value, final Instant at) {
		return new MarketRate("USDT", "ZAR", new BigDecimal(value), at);
	}

}
