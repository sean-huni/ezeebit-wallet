package io.ezeebit.ledger.service.conversion.answer;

import java.util.UUID;
import java.util.function.Supplier;

import io.ezeebit.ledger.common.metrics.LedgerMetrics;
import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.exception.LedgerException;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Every answer is counted once, on its span too: its outcome, or the code of the refusal it gave (a replayed
// refusal included).
@Component
public class ConversionPostingResponder {

	private static final Logger LOG = LoggerFactory.getLogger(ConversionPostingResponder.class);

	private final ConversionPostingReader reader;
	private final LedgerMetrics metrics;

	public ConversionPostingResponder(final ConversionPostingReader reader, final LedgerMetrics metrics) {
		this.reader = reader;
		this.metrics = metrics;
	}

	public ConversionPostingResp respond(final String operation, final Supplier<Outcome> action, final MerchantId merchant,
			final UUID conversionId) {
		Observation span = metrics.postingSpan(operation);
		return metrics.observe(span, () -> answer(span, operation, action, merchant, conversionId));
	}

	private ConversionPostingResp answer(final Observation span, final String operation, final Supplier<Outcome> action,
			final MerchantId merchant, final UUID conversionId) {
		try {
			Outcome outcome = action.get();
			ConversionPostingResp answer = reader.read(merchant, conversionId);
			metrics.conversionPosting(span, outcome, null);
			LOG.info("conversion {} {}", operation, outcome);
			return answer;
		}
		catch (LedgerException refusal) {
			metrics.conversionPosting(span, Outcome.REFUSED, refusal.code());
			LOG.info("conversion {} refused: {}", operation, refusal.code());
			throw refusal;
		}
	}

}
