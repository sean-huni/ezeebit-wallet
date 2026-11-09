package io.ezeebit.ledger.mapper;

import java.util.UUID;

import io.ezeebit.ledger.data.model.EntryEntity;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.PostingLine;
import org.jspecify.annotations.Nullable;

public interface EntryMapper {

	EntryEntity toEntity(UUID journalId, PostingLine line, @Nullable Money balanceAfter);

}
