package io.ezeebit.ledger.service;

import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.JournalStatus;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.MerchantId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// A VOID journal has no entries. It occupies the conversion's identity, so a posting that arrives later conflicts.
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
				"void conversion %s".formatted(conversionId), null));
		return journalId;
	}

}
