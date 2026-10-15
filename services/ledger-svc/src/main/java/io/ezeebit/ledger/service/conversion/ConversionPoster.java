package io.ezeebit.ledger.service.conversion;

import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.DbClockRepo;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.JournalStatus;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// One conversion, one transaction: journal, four entries and both merchant balances commit together or not at all.
@Service
public class ConversionPoster {

	private final JournalRepo journals;
	private final ConversionLegWriter legs;
	private final DbClockRepo clock;

	public ConversionPoster(final JournalRepo journals, final ConversionLegWriter legs, final DbClockRepo clock) {
		this.journals = journals;
		this.legs = legs;
		this.clock = clock;
	}

	// The journal goes in first: a duplicate or a VOID marker under this identity fails here, before any lock.
	@Transactional
	public UUID post(final ConversionPostingCommand command) {
		UUID journalId = UUID.randomUUID();
		journals.insert(new JournalEntity(journalId, command.merchant().value(), JournalType.CONVERSION.name(),
				JournalStatus.POSTED.name(), command.conversionId().toString(), command.requestHash(),
				"conversion %s".formatted(command.conversionId()), null, null));
		legs.write(journalId, command);
		// Last, so nothing that commits was decided after the deadline; the exception rolls everything back.
		if (clock.now().isAfter(command.deadline())) {
			throw new LedgerException(ErrorCode.POSTING_DEADLINE_PASSED, "deadline", command.deadline().toString());
		}
		return journalId;
	}

}
