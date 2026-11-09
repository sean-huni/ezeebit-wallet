package io.ezeebit.ledger.service.posting;

import java.util.UUID;

import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.PostingLine;
import org.jspecify.annotations.Nullable;

public interface EntryWriter {

	void write(UUID journalId, PostingLine line, @Nullable Money balanceAfter);

}
