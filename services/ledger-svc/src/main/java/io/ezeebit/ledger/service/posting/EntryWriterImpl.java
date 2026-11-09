package io.ezeebit.ledger.service.posting;

import java.util.UUID;

import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.PostingLine;
import io.ezeebit.ledger.mapper.EntryMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class EntryWriterImpl implements EntryWriter {

	private final EntryRepo entries;
	private final EntryMapper mapper;

	EntryWriterImpl(final EntryRepo entries, final EntryMapper mapper) {
		this.entries = entries;
		this.mapper = mapper;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	@Override
	public void write(final UUID journalId, final PostingLine line, @Nullable final Money balanceAfter) {
		entries.insert(mapper.toEntity(journalId, line, balanceAfter));
	}

}
