package io.ezeebit.conversion.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.RateRejection;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ConversionMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final ConversionMetrics metrics = new ConversionMetrics(registry);

	@Test
	void anIssuedQuoteIsCountedByPairWithNoReason() {
		metrics.quoteIssued("ZAR/USDT");

		assertThat(registry.get(ConversionMetrics.QUOTES).tag("pair", "ZAR/USDT").tag("outcome", "issued")
				.tag("reason", "none").tag("detail", "none").counter().count()).isEqualTo(1.0);
	}

	@Test
	void aRejectedRateIsCountedWithItsCodeAndTheRuleItBroke() {
		metrics.quoteRefused("ZAR/USDT", RateValidator.rejected(RateRejection.RATE_STALE));

		assertThat(registry.get(ConversionMetrics.QUOTES).tag("outcome", "refused").tag("reason", "RATE_REJECTED")
				.tag("detail", "RATE_STALE").counter().count()).isEqualTo(1.0);
	}

	@Test
	void anyOtherRefusalCarriesItsCodeAndNoDetail() {
		metrics.quoteRefused("ZAR/USDT", new ConversionException(ErrorCode.RATE_UNAVAILABLE, null, "ZAR", "USDT"));
		metrics.quoteRefused("ZAR/USDT", new ConversionException(ErrorCode.AMOUNT_TOO_SMALL, "sellAmount")
				.with("reason", "made up by a caller"));

		assertThat(registry.get(ConversionMetrics.QUOTES).tag("reason", "RATE_UNAVAILABLE").tag("detail", "none")
				.counter().count()).isEqualTo(1.0);
		assertThat(registry.get(ConversionMetrics.QUOTES).tag("reason", "AMOUNT_TOO_SMALL").tag("detail", "none")
				.counter().count()).isEqualTo(1.0);
	}

	@Test
	void rateCallsAreTimedByOutcomeWithBucketsForAPercentile() {
		metrics.rateCall(Duration.ofMillis(40), "ok");
		metrics.rateCall(Duration.ofMillis(2100), "unavailable");

		Timer ok = registry.get(ConversionMetrics.RATE_CALLS).tag("outcome", "ok").timer();
		assertThat(ok.count()).isEqualTo(1);
		assertThat(ok.takeSnapshot().histogramCounts()).extracting(CountAtBucket::bucket).isNotEmpty();
		assertThat(registry.get(ConversionMetrics.RATE_CALLS).tag("outcome", "unavailable").timer().count())
				.isEqualTo(1);
	}

	@Test
	void conversionsAreCountedByStatusAndReason() {
		metrics.conversion(ConversionStatus.COMPLETED, null);
		metrics.conversion(ConversionStatus.REJECTED, RejectionReason.INSUFFICIENT_FUNDS);
		metrics.conversion(ConversionStatus.PENDING, null);

		assertThat(registry.get(ConversionMetrics.OUTCOMES).tag("status", "COMPLETED").tag("reason", "none")
				.counter().count()).isEqualTo(1.0);
		assertThat(registry.get(ConversionMetrics.OUTCOMES).tag("status", "REJECTED")
				.tag("reason", "INSUFFICIENT_FUNDS").counter().count()).isEqualTo(1.0);
		assertThat(registry.get(ConversionMetrics.OUTCOMES).tag("status", "PENDING").counter().count())
				.isEqualTo(1.0);
	}

	@Test
	void eachSweeperAnswerIsCountedAsTheActionItTook() {
		metrics.sweep(LedgerAnswer.POSTED);
		metrics.sweep(LedgerAnswer.VOIDED);
		metrics.sweep(LedgerAnswer.INSUFFICIENT_FUNDS);
		metrics.sweep(LedgerAnswer.UNKNOWN);

		for (String action : new String[] { "completed", "voided", "refused", "unresolved" }) {
			assertThat(registry.get(ConversionMetrics.SWEEPS).tag("action", action).counter().count()).as(action)
					.isEqualTo(1.0);
		}
	}

}
