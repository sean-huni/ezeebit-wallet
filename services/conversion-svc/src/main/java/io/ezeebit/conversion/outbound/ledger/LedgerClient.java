package io.ezeebit.conversion.outbound.ledger;

import java.util.Map;
import java.util.UUID;

import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.service.port.LedgerPosting;
import io.ezeebit.conversion.service.port.LedgerPostings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// Calls ledger-svc by its registry name. Any transport failure is UNKNOWN: the posting may have committed.
@Component
public class LedgerClient implements LedgerPostings {

	private static final Logger LOG = LoggerFactory.getLogger(LedgerClient.class);
	private static final String POSTINGS = "/internal/v1/conversion-postings";

	private final RestClient ledger;
	private final LedgerAnswerMapper mapper;
	private final PostingMapper postings;

	public LedgerClient(@Qualifier("ledgerRestClient") final RestClient ledger, final LedgerAnswerMapper mapper,
			final PostingMapper postings) {
		this.ledger = ledger;
		this.mapper = mapper;
		this.postings = postings;
	}

	@Override
	public LedgerAnswer post(final LedgerPosting posting) {
		PostingDto body = postings.toPosting(posting.conversionId(), posting.merchantId(), posting.quote(),
				posting.deadline());
		return call(() -> ledger.post().uri(POSTINGS).contentType(MediaType.APPLICATION_JSON).body(body));
	}

	// A 400 on a lookup or a void is about this caller's request, never the conversion: the outcome stays open.
	@Override
	public LedgerAnswer lookup(final UUID conversionId, final UUID merchantId) {
		return aboutTheConversion(call(() -> ledger.get().uri(POSTINGS + "/{id}?merchantId={merchant}", conversionId,
				merchantId)));
	}

	@Override
	public LedgerAnswer voidPosting(final UUID conversionId, final UUID merchantId) {
		return aboutTheConversion(call(() -> ledger.post().uri(POSTINGS + "/{id}/void", conversionId)
				.contentType(MediaType.APPLICATION_JSON).body(Map.of("merchantId", merchantId))));
	}

	private static LedgerAnswer aboutTheConversion(final LedgerAnswer answer) {
		return answer == LedgerAnswer.INPUT_REFUSED ? LedgerAnswer.UNKNOWN : answer;
	}

	private LedgerAnswer call(final java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request) {
		try {
			return request.get().exchange((req, resp) -> mapper.toDomain(resp.getStatusCode().value(), read(resp)));
		}
		catch (RestClientException ex) {
			LOG.warn("ledger outcome unknown: {}", LogText.oneLine(ex.getMessage()));
			return LedgerAnswer.UNKNOWN;
		}
	}

	private static LedgerReplyDto read(final RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
		try {
			return response.bodyTo(LedgerReplyDto.class);
		}
		catch (RestClientException ex) {
			return null;
		}
	}

}
