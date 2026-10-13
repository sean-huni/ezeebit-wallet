package io.ezeebit.conversion.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import io.ezeebit.conversion.domain.RateRejection;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class ConversionSpansTest {

	private final TestObservationRegistry observations = TestObservationRegistry.create();
	private final ConversionSpans spans = new ConversionSpans(observations);

	@Test
	void anIssuedQuoteIsOneSpanTaggedWithItsPairAndOutcomeOnly() {
		Observation span = spans.quote("ZAR/USDT");

		spans.observe(span, () -> {
			spans.quoteIssued(span);
			return null;
		});

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasNameEqualTo(ConversionSpans.QUOTE).hasContextualNameEqualTo("quote").hasBeenStopped()
				.doesNotHaveError().hasLowCardinalityKeyValue("pair", "ZAR/USDT")
				.hasLowCardinalityKeyValue("outcome", "issued").hasLowCardinalityKeyValue("reason", "none")
				.hasOnlyKeys("pair", "outcome", "reason", "detail");
	}

	// A refusal is an answer: tagged with its code and rule, never recorded as an error whose message echoes input.
	@Test
	void aRefusedQuoteCarriesItsReasonAndTheRuleItBrokeAndIsNotAnError() {
		Observation span = spans.quote("ZAR/USDT");
		ConversionException stale = RateValidator.rejected(RateRejection.RATE_STALE);

		assertThatThrownBy(() -> spans.observe(span, () -> {
			spans.quoteRefused(span, stale);
			throw stale;
		})).isSameAs(stale);

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat().hasBeenStopped()
				.doesNotHaveError().hasLowCardinalityKeyValue("outcome", "refused")
				.hasLowCardinalityKeyValue("reason", "RATE_REJECTED").hasLowCardinalityKeyValue("detail", "RATE_STALE");
	}

	@Test
	void anAcceptedConversionCarriesItsPairAndStatus() {
		Observation span = spans.accept();

		spans.observe(span, () -> {
			spans.pair(span, "ZAR", "USDT");
			spans.accepted(span, "PENDING");
			return null;
		});

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasNameEqualTo(ConversionSpans.ACCEPT).hasContextualNameEqualTo("conversion accept")
				.hasLowCardinalityKeyValue("pair", "ZAR/USDT").hasLowCardinalityKeyValue("outcome", "pending")
				.hasLowCardinalityKeyValue("code", "none").hasOnlyKeys("pair", "outcome", "code");
	}

	@Test
	void aConversionTheLedgerRejectedIsRejectedAndARefusedRequestIsRefused() {
		Observation rejected = spans.accept();
		Observation refused = spans.accept();

		spans.acceptRefused(rejected, new ConversionException(ErrorCode.INSUFFICIENT_FUNDS, null)
				.with("conversionId", "c1").with("reason", "INSUFFICIENT_FUNDS"));
		spans.acceptRefused(refused, new ConversionException(ErrorCode.QUOTE_EXPIRED, "quoteId", "q1"));

		TestObservationRegistryAssert.assertThat(observations).doesNotHaveAnyObservation();
		assertThat(rejected.getContext().getLowCardinalityKeyValue("outcome").getValue())
				.isEqualTo("rejected");
		assertThat(refused.getContext().getLowCardinalityKeyValue("code").getValue())
				.isEqualTo("QUOTE_EXPIRED");
		assertThat(refused.getContext().getLowCardinalityKeyValue("outcome").getValue())
				.isEqualTo("refused");
	}

	@Test
	void aSweepRunCarriesTheCountOfEachActionAsSpanAttributesNotTags() {
		Observation span = spans.sweep();

		spans.observe(span, () -> {
			spans.swept(span, Map.of("completed", 2, "voided", 1));
			return null;
		});

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasNameEqualTo(ConversionSpans.SWEEP).hasLowCardinalityKeyValue("result", "resolved")
				.hasHighCardinalityKeyValue("sweep.claimed", "3").hasHighCardinalityKeyValue("sweep.completed", "2")
				.hasHighCardinalityKeyValue("sweep.voided", "1").hasHighCardinalityKeyValue("sweep.refused", "0")
				.hasHighCardinalityKeyValue("sweep.unresolved", "0");
	}

	@Test
	void anIdleSweepSaysSo() {
		Observation span = spans.sweep();

		spans.observe(span, () -> {
			spans.swept(span, Map.of());
			return null;
		});

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat()
				.hasLowCardinalityKeyValue("result", "idle").hasHighCardinalityKeyValue("sweep.claimed", "0");
	}

	@Test
	void aDatabaseFailureIsRecordedOnTheSpanAndLeavesTheOutcomeAsError() {
		Observation span = spans.accept();
		DataAccessResourceFailureException down = new DataAccessResourceFailureException("database unreachable");

		assertThatThrownBy(() -> spans.observe(span, () -> {
			throw down;
		})).isSameAs(down);

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat().hasBeenStopped()
				.hasError(down).hasLowCardinalityKeyValue("outcome", "error").hasLowCardinalityKeyValue("pair", "unknown");
	}

	// The transaction manager's own failure (a rollback that cannot complete) replaces the database error that caused it.
	@Test
	void aTransactionFailureIsRecordedOnTheSpanToo() {
		Observation span = spans.accept();
		TransactionSystemException rollback = new TransactionSystemException("could not roll back");

		assertThatThrownBy(() -> spans.observe(span, () -> {
			throw rollback;
		})).isSameAs(rollback);

		TestObservationRegistryAssert.assertThat(observations).hasSingleObservationThat().hasError(rollback);
	}

}
