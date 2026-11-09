package io.ezeebit.ledger.data.repo;

import java.time.Instant;

public interface DbClockRepo {

	Instant now();

}
