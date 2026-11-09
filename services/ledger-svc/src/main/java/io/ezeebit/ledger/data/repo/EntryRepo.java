package io.ezeebit.ledger.data.repo;

import java.util.List;
import java.util.UUID;

import io.ezeebit.ledger.data.model.EntryEntity;
import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.data.repo.query.StatementFilter;

public interface EntryRepo {

	void insert(EntryEntity entry);

	StatementLineEntity merchantLine(UUID journalId);

	List<StatementLineEntity> merchantLines(UUID journalId);

	List<StatementLineEntity> statement(StatementFilter filter);

}
