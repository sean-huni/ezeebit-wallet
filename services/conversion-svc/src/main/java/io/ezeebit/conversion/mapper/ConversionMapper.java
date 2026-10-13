package io.ezeebit.conversion.mapper;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.outbound.ledger.PostingDto;
import org.springframework.stereotype.Component;

@Component
public class ConversionMapper {

	public ConversionResp toResp(final ConversionEntity conversion, final Quote quote) {
		return new ConversionResp(conversion.id(), conversion.merchantId(), conversion.quoteId(), conversion.status(),
				conversion.reason(), quote.sell().currency().code(), quote.sell().text(), quote.buy().currency().code(),
				quote.buy().text(), quote.appliedRate().stripTrailingZeros().toPlainString(), conversion.created(),
				conversion.completed());
	}

	// Exactly the quoted amounts, at each currency's scale: the merchant gets the quoted rate or nothing.
	public PostingDto toPosting(final ConversionEntity conversion, final Quote quote) {
		return new PostingDto(conversion.id(), conversion.merchantId(), quote.sell().currency().code(),
				quote.sell().text(), quote.buy().currency().code(), quote.buy().text(), conversion.deadline());
	}

}
