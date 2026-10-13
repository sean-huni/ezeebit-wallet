package io.ezeebit.ledger.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.CurrencyCode;
import io.ezeebit.ledger.domain.CurrencyKind;
import io.ezeebit.ledger.exception.ErrorCode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LedgerMetricsTest {

	private static final Currency ZAR = new Currency(new CurrencyCode("ZAR"), CurrencyKind.FIAT, 2);

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final LedgerMetrics metrics = new LedgerMetrics(registry);

	@Test
	void aPostedDepositIsCountedByCurrencyWithNoCode() {
		metrics.deposit(ZAR, Outcome.POSTED, null);

		assertThat(registry.get(LedgerMetrics.DEPOSITS).tag("currency", "ZAR").tag("outcome", "posted")
				.tag("code", "none").counter().count()).isEqualTo(1.0);
	}

	@Test
	void aRefusedDepositCarriesItsCode() {
		metrics.deposit(ZAR, Outcome.REFUSED, ErrorCode.BALANCE_LIMIT_EXCEEDED);

		assertThat(registry.get(LedgerMetrics.DEPOSITS).tag("outcome", "refused")
				.tag("code", "BALANCE_LIMIT_EXCEEDED").counter().count()).isEqualTo(1.0);
	}

	@Test
	void aDepositInAnUnknownCurrencyNeverPutsTheCallersTextIntoATag() {
		metrics.deposit(null, Outcome.REFUSED, ErrorCode.UNKNOWN_CURRENCY);

		assertThat(registry.get(LedgerMetrics.DEPOSITS).tag("currency", "unknown").counter().count()).isEqualTo(1.0);
	}

	@Test
	void conversionPostingsAreCountedByOutcomeAndCode() {
		metrics.conversionPosting(Outcome.POSTED, null);
		metrics.conversionPosting(Outcome.VOIDED, null);
		metrics.conversionPosting(Outcome.REFUSED, ErrorCode.INSUFFICIENT_FUNDS);

		assertThat(registry.get(LedgerMetrics.CONVERSION_POSTINGS).tag("outcome", "posted").tag("code", "none")
				.counter().count()).isEqualTo(1.0);
		assertThat(registry.get(LedgerMetrics.CONVERSION_POSTINGS).tag("outcome", "voided").counter().count())
				.isEqualTo(1.0);
		assertThat(registry.get(LedgerMetrics.CONVERSION_POSTINGS).tag("outcome", "refused")
				.tag("code", "INSUFFICIENT_FUNDS").counter().count()).isEqualTo(1.0);
	}

	@Test
	void noMeterCarriesAnIdentifier() {
		metrics.deposit(ZAR, Outcome.POSTED, null);
		metrics.conversionPosting(Outcome.POSTED, null);

		assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
				.extracting(tag -> tag.getKey()).containsOnly("currency", "outcome", "code");
		assertThat(registry.getMeters()).extracting(Meter::getId).hasSize(2);
	}

}
