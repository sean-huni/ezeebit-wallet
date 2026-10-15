package io.ezeebit.ledger.service.conversion;

import java.util.UUID;

import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.service.conversion.answer.ConversionPostingResponder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Not transactional itself: a duplicate identity must be caught outside the transaction it rolled back.
@Service
public class ConversionVoidService {

	private final ConversionVoider voider;
	private final ConversionReplayer replayer;
	private final ConversionPostingResponder responder;

	public ConversionVoidService(final ConversionVoider voider, final ConversionReplayer replayer,
			final ConversionPostingResponder responder) {
		this.voider = voider;
		this.replayer = replayer;
		this.responder = responder;
	}

	public ConversionPostingResp voidPosting(final UUID conversionId, final UUID merchantId) {
		MerchantId merchant = new MerchantId(merchantId);
		return responder.respond("void", () -> voidOrReplay(merchant, conversionId), merchant, conversionId);
	}

	private Outcome voidOrReplay(final MerchantId merchant, final UUID conversionId) {
		try {
			voider.voidPosting(merchant, conversionId);
			return Outcome.VOIDED;
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedVoid(merchant, conversionId);
			return Outcome.REPLAYED;
		}
	}

}
