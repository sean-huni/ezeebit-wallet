package io.ezeebit.ledger.mapper;

import java.util.UUID;

import io.ezeebit.ledger.data.model.EntryEntity;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.PostingLine;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class EntryMapperImpl implements EntryMapper {

	@Override
	public EntryEntity toEntity(final UUID journalId, final PostingLine line, @Nullable final Money balanceAfter) {
		return new EntryEntity(UUID.randomUUID(), journalId, line.accountId(), line.money().currency().value(),
				line.money().amount(), balanceAfter == null ? null : balanceAfter.amount());
	}

}
