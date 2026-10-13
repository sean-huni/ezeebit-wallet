package io.ezeebit.ledger.service;

import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.JournalStatus;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// VOID and REFUSED journals have no entries. Each occupies the conversion's identity, so a later posting conflicts.
@Service
public class ConversionVoider {

	private final JournalRepo journals;

	public ConversionVoider(final JournalRepo journals) {
		this.journals = journals;
	}

	@Transactional
	public UUID voidPosting(final MerchantId merchant, final UUID conversionId, final String requestHash) {
		UUID journalId = UUID.randomUUID();
		journals.insert(new JournalEntity(journalId, merchant.value(), JournalType.CONVERSION.name(),
				JournalStatus.VOID.name(), conversionId.toString(), requestHash,
				"void conversion %s".formatted(conversionId), null, null));
		return journalId;
	}

	// Committed before the refusal is answered, so a refusal the caller sees can never be followed by a posting.
	@Transactional
	public UUID refuse(final MerchantId merchant, final UUID conversionId, final String requestHash,
			final ErrorCode reason) {
		UUID journalId = UUID.randomUUID();
		journals.insert(new JournalEntity(journalId, merchant.value(), JournalType.CONVERSION.name(),
				JournalStatus.REFUSED.name(), conversionId.toString(), requestHash,
				"refused conversion %s".formatted(conversionId), reason.name(), null));
		return journalId;
	}

}
