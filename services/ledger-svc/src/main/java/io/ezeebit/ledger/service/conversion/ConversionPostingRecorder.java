package io.ezeebit.ledger.service.conversion;

import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

// Not transactional itself: a duplicate identity must be caught outside the transaction it rolled back.
@Component
public class ConversionPostingRecorder {

	private final ConversionPoster poster;
	private final ConversionReplayer replayer;
	private final RefusalRecorder refusals;

	public ConversionPostingRecorder(final ConversionPoster poster, final ConversionReplayer replayer,
			final RefusalRecorder refusals) {
		this.poster = poster;
		this.replayer = replayer;
		this.refusals = refusals;
	}

	public Outcome record(final ConversionPostingCommand command, final ConversionPostingReq request) {
		try {
			poster.post(command);
			return Outcome.POSTED;
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedPosting(command.merchant(), request.conversionId(), command.requestHash(),
					request.sellCurrency(), request.buyCurrency());
		}
		catch (LedgerException refusal) {
			refusals.recordAndRethrow(command.merchant(), request, command.requestHash(), refusal);
		}
		return Outcome.REPLAYED;
	}

}
