package io.ezeebit.ledger.service.posting;

import java.util.UUID;

import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.PostingLine;
import io.ezeebit.ledger.mapper.EntryMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

// Runs inside the caller's posting transaction.
@Component
public class EntryWriter {

	private final EntryRepo entries;
	private final EntryMapper mapper;

	public EntryWriter(final EntryRepo entries, final EntryMapper mapper) {
		this.entries = entries;
		this.mapper = mapper;
	}

	public void write(final UUID journalId, final PostingLine line, @Nullable final Money balanceAfter) {
		entries.insert(mapper.toEntity(journalId, line, balanceAfter));
	}

}
