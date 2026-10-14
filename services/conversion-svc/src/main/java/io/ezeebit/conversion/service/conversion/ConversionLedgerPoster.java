package io.ezeebit.conversion.service.conversion;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.service.port.LedgerPosting;
import io.ezeebit.conversion.service.port.LedgerPostings;
import io.ezeebit.conversion.service.quote.QuoteReader;
import org.springframework.stereotype.Service;

@Service
public class ConversionLedgerPoster {

	private final LedgerPostings ledger;
	private final QuoteReader quotes;
	private final ConversionSettler settler;

	public ConversionLedgerPoster(final LedgerPostings ledger, final QuoteReader quotes, final ConversionSettler settler) {
		this.ledger = ledger;
		this.quotes = quotes;
		this.settler = settler;
	}

	// The ledger keeps one journal per conversion id, POSTED, VOID or REFUSED, and replays it: posting again
	// returns the first outcome, so money moves at most once and never after a recorded refusal.
	public ConversionEntity postAndSettle(final ConversionEntity conversion) {
		LedgerAnswer answer = ledger.post(new LedgerPosting(conversion.id(), conversion.merchantId(),
				quotes.of(conversion.quote()), conversion.deadline()));
		return settler.settleCommitted(conversion, answer);
	}

}
