package io.ezeebit.persistence;

import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LockException;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.StandardLockService;

// A lock row outlives a SIGKILLed migrator and blocks every later pod; a session advisory lock dies with its connection.
// Waiting is inherited, so changelogLockWaitTimeInMinutes still bounds it and a wedged holder fails a start, never hangs it.
public class AdvisoryLockService extends StandardLockService {

	public static final long KEY = 0x657a656562697400L;

	private boolean held;

	@Override
	public int getPriority() {
		return PRIORITY_DATABASE + 10;
	}

	@Override
	public boolean supports(final Database candidate) {
		return candidate instanceof PostgresDatabase && candidate.getConnection() instanceof JdbcConnection;
	}

	@Override
	public boolean hasChangeLogLock() {
		return held;
	}

	@Override
	public boolean acquireLock() throws LockException {
		if (!held && calls().tryLock()) {
			held = true;
			// The wait may have outlasted another pod's migration, so the history is read again, as the core service does.
			Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class).resetAll();
			database.setCanCacheLiquibaseTableInfo(true);
		}
		return held;
	}

	@Override
	public void releaseLock() throws LockException {
		if (!held) {
			return;
		}
		try {
			if (!calls().unlock()) {
				throw new LockException("advisory lock %d was not held by this session".formatted(KEY));
			}
		}
		finally {
			held = false;
			database.setCanCacheLiquibaseTableInfo(false);
		}
	}

	@Override
	public void forceReleaseLock() throws LockException {
		releaseLock();
	}

	@Override
	public void reset() {
		// Only the caches: the session still holds the lock, and forgetting it would leak it onto a pooled connection.
		boolean stillHeld = held;
		super.reset();
		held = stillHeld;
	}

	@Override
	public DatabaseChangeLogLock[] listLocks() throws LockException {
		return calls().holders();
	}

	private AdvisoryLockCalls calls() {
		return new AdvisoryLockCalls(database, KEY);
	}

}
