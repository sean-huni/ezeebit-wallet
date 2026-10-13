package io.ezeebit.conversion.outbound.ledger;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.domain.Quote;
import org.springframework.stereotype.Component;

// Translates this context's conversion into the ledger's posting request; LedgerAnswerMapper translates the reply.
@Component
public class PostingMapper {

	// Exactly the quoted amounts, at each currency's scale: the merchant gets the quoted rate or nothing.
	public PostingDto toPosting(final UUID conversionId, final UUID merchantId, final Quote quote,
			final Instant deadline) {
		return new PostingDto(conversionId, merchantId, quote.sell().currency().code(), quote.sell().text(),
				quote.buy().currency().code(), quote.buy().text(), deadline);
	}

}
