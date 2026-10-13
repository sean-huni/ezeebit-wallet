package io.ezeebit.ledger.service;

import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.JournalStatus;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Runs after the losing insert rolled back: whatever holds the identity has committed and is visible.
@Service
public class ConversionReplayer {

	private final JournalRepo journals;

	public ConversionReplayer(final JournalRepo journals) {
		this.journals = journals;
	}

	// A refusal replays as the same refusal whatever is sent: money that arrived since changes nothing.
	@Transactional(readOnly = true)
	public UUID storedPosting(final MerchantId merchant, final UUID conversionId, final String requestHash,
			final String sellCurrency, final String buyCurrency) {
		JournalEntity stored = stored(merchant, conversionId);
		if (JournalStatus.REFUSED.name().equals(stored.status())) {
			ErrorCode refusal = ErrorCode.valueOf(stored.reason());
			throw new LedgerException(refusal, null,
					refusal == ErrorCode.INSUFFICIENT_FUNDS ? sellCurrency : buyCurrency);
		}
		if (JournalStatus.VOID.name().equals(stored.status())) {
			throw new LedgerException(ErrorCode.CONVERSION_VOIDED, "conversionId", conversionId.toString());
		}
		if (!stored.requestHash().equals(requestHash)) {
			throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "conversionId", conversionId.toString());
		}
		return stored.id();
	}

	// VOID or REFUSED: either way no posting can land, so the stored marker is the answer.
	@Transactional(readOnly = true)
	public UUID storedVoid(final MerchantId merchant, final UUID conversionId) {
		JournalEntity stored = stored(merchant, conversionId);
		if (JournalStatus.POSTED.name().equals(stored.status())) {
			throw new LedgerException(ErrorCode.CONVERSION_ALREADY_POSTED, "conversionId", conversionId.toString());
		}
		return stored.id();
	}

	private JournalEntity stored(final MerchantId merchant, final UUID conversionId) {
		return journals.findByIdentity(merchant.value(), JournalType.CONVERSION.name(), conversionId.toString())
				.orElseThrow(() -> new IllegalStateException("duplicate key reported but no journal for %s"
						.formatted(conversionId)));
	}

}
