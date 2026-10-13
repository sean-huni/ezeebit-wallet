package io.ezeebit.ledger.service.conversion;

import java.util.EnumSet;
import java.util.Set;

import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Balance-dependent refusals are made final before they are answered; otherwise money arriving later
// would let a retry of the same conversion post after its caller had recorded it as rejected.
@Service
public class RefusalRecorder {

	private static final Set<ErrorCode> FINAL_REFUSALS = EnumSet.of(ErrorCode.INSUFFICIENT_FUNDS,
			ErrorCode.BALANCE_LIMIT_EXCEEDED);

	private final ConversionVoider voider;
	private final ConversionReplayer replayer;

	public RefusalRecorder(final ConversionVoider voider, final ConversionReplayer replayer) {
		this.voider = voider;
		this.replayer = replayer;
	}

	// Returns only when a concurrent copy holds the identity as POSTED: the caller then answers that posting.
	public void recordAndRethrow(final MerchantId merchant, final ConversionPostingReq request, final String hash,
			final LedgerException refusal) {
		if (!FINAL_REFUSALS.contains(refusal.code())) {
			throw refusal;
		}
		try {
			voider.refuse(merchant, request.conversionId(), hash, refusal.code());
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedPosting(merchant, request.conversionId(), hash, request.sellCurrency(), request.buyCurrency());
			return;
		}
		throw refusal;
	}

}
