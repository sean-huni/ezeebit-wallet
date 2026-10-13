package io.ezeebit.ledger.service.deposit;

import java.util.UUID;

import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.DepositResp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

// Not transactional itself: the duplicate key must be caught outside the transaction it rolled back.
@Component
public class DepositRecorder {

	private final DepositPoster poster;
	private final DepositReplayer replayer;
	private final DepositReader reader;

	public DepositRecorder(final DepositPoster poster, final DepositReplayer replayer, final DepositReader reader) {
		this.poster = poster;
		this.replayer = replayer;
		this.reader = reader;
	}

	// First call and every replay answer from the stored rows, so a replay is byte-identical.
	public Recorded record(final DepositCommand command, final Currency currency) {
		UUID journalId;
		Outcome outcome = Outcome.POSTED;
		try {
			journalId = poster.post(command);
		}
		catch (DuplicateKeyException duplicate) {
			journalId = replayer.storedJournal(command.merchant(), command.idempotencyKey(), command.requestHash());
			outcome = Outcome.REPLAYED;
		}
		return new Recorded(reader.read(journalId, command.merchant(), currency), outcome);
	}

	public record Recorded(DepositResp answer, Outcome outcome) {
	}

}
