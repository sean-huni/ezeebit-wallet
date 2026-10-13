package io.ezeebit.ledger.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.CurrencyCode;
import io.ezeebit.ledger.domain.CurrencyKind;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class LedgerMetricsTest {

	private static final Currency ZAR = new Currency(new CurrencyCode("ZAR"), CurrencyKind.FIAT, 2);

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final TestObservationRegistry observations = TestObservationRegistry.create();
	private final LedgerMetrics metrics = new LedgerMetrics(registry, observations);

	@Test
	void aPostedDepositIsCountedByCurrencyWithNoCode() {
		metrics.deposit(Observation.NOOP, ZAR, Outcome.POSTED, null);

		assertThat(registry.get(LedgerMetrics.DEPOSITS).tag("currency", "ZAR").tag("outcome", "posted")
				.tag("code", "none").counter().count()).isEqualTo(1.0);
	}

	@Test
	void aRefusedDepositCarriesItsCode() {
		metrics.deposit(Observation.NOOP, ZAR, Outcome.REFUSED, ErrorCode.BALANCE_LIMIT_EXCEEDED);

		assertThat(registry.get(LedgerMetrics.DEPOSITS).tag("outcome", "refused")
				.tag("code", "BALANCE_LIMIT_EXCEEDED").counter().count()).isEqualTo(1.0);
	}

	@Test
	void aDepositInAnUnknownCurrencyNeverPutsTheCallersTextIntoATag() {
		metrics.deposit(Observation.NOOP, null, Outcome.REFUSED, ErrorCode.UNKNOWN_CURRENCY);

		assertThat(registry.get(LedgerMetrics.DEPOSITS).tag("currency", "unknown").counter().count()).isEqualTo(1.0);
	}

	@Test
	void conversionPostingsAreCountedByOutcomeAndCode() {
		metrics.conversionPosting(Observation.NOOP, Outcome.POSTED, null);
		metrics.conversionPosting(Observation.NOOP, Outcome.VOIDED, null);
		metrics.conversionPosting(Observation.NOOP, Outcome.REFUSED, ErrorCode.INSUFFICIENT_FUNDS);

		assertThat(registry.get(LedgerMetrics.CONVERSION_POSTINGS).tag("outcome", "posted").tag("code", "none")
				.counter().count()).isEqualTo(1.0);
		assertThat(registry.get(LedgerMetrics.CONVERSION_POSTINGS).tag("outcome", "voided").counter().count())
				.isEqualTo(1.0);
		assertThat(registry.get(LedgerMetrics.CONVERSION_POSTINGS).tag("outcome", "refused")
				.tag("code", "INSUFFICIENT_FUNDS").counter().count()).isEqualTo(1.0);
	}

	@Test
	void noMeterCarriesAnIdentifier() {
		metrics.deposit(Observation.NOOP, ZAR, Outcome.POSTED, null);
		metrics.conversionPosting(Observation.NOOP, Outcome.POSTED, null);

		assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
				.extracting(tag -> tag.getKey()).containsOnly("currency", "outcome", "code");
		assertThat(registry.getMeters()).extracting(Meter::getId).hasSize(2);
	}

	@Test
	void aDepositIsOneSpanCarryingItsCurrencyAndOutcomeAndNothingElse() {
		Observation span = metrics.depositSpan();

		metrics.observe(span, () -> {
			metrics.deposit(span, ZAR, Outcome.POSTED, null);
			return null;
		});

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasNameEqualTo(LedgerMetrics.DEPOSIT_SPAN).hasContextualNameEqualTo("deposit")
				.hasBeenStarted().hasBeenStopped().doesNotHaveError()
				.hasLowCardinalityKeyValue("currency", "ZAR").hasLowCardinalityKeyValue("outcome", "posted")
				.hasLowCardinalityKeyValue("code", "none").hasOnlyKeys("currency", "outcome", "code");
	}

	// A refusal is an answer, not a fault: tagged as its code, never recorded as an error whose message echoes input.
	@Test
	void aRefusedPostingIsTaggedWithItsCodeAndIsNotAnError() {
		Observation span = metrics.postingSpan("post");

		assertThatThrownBy(() -> metrics.observe(span, () -> {
			metrics.conversionPosting(span, Outcome.REFUSED, ErrorCode.INSUFFICIENT_FUNDS);
			throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, null, "ZAR");
		})).isInstanceOf(LedgerException.class);

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasNameEqualTo(LedgerMetrics.POSTING_SPAN).hasContextualNameEqualTo("ledger posting")
				.hasBeenStopped().doesNotHaveError().hasLowCardinalityKeyValue("operation", "post")
				.hasLowCardinalityKeyValue("outcome", "refused").hasLowCardinalityKeyValue("code", "INSUFFICIENT_FUNDS");
	}

	@Test
	void aDatabaseFailureIsRecordedOnTheSpanAndLeavesTheOutcomeAsError() {
		Observation span = metrics.depositSpan();
		DataAccessResourceFailureException down = new DataAccessResourceFailureException("database unreachable");

		assertThatThrownBy(() -> metrics.observe(span, () -> {
			throw down;
		})).isSameAs(down);

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat().hasBeenStopped()
				.hasError(down).hasLowCardinalityKeyValue("outcome", "error");
	}

}
