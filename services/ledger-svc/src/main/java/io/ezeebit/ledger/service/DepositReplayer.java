package io.ezeebit.ledger.service;

import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Runs after the losing insert rolled back: the winner has committed, so its journal is visible.
@Service
public class DepositReplayer {

	private final JournalRepo journals;

	public DepositReplayer(final JournalRepo journals) {
		this.journals = journals;
	}

	@Transactional(readOnly = true)
	public UUID storedJournal(final MerchantId merchant, final String key, final String requestHash) {
		JournalEntity stored = journals.findByIdentity(merchant.value(), JournalType.DEPOSIT.name(), key)
				.orElseThrow(() -> new IllegalStateException("duplicate key reported but no journal for %s".formatted(key)));
		if (!stored.requestHash().equals(requestHash)) {
			throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key", key);
		}
		return stored.id();
	}

}
