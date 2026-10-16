package io.ezeebit.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.StandardLockService;

// A lock row outlives a SIGKILLed migrator and blocks every later pod; a session advisory lock dies with its connection.
// Waiting is inherited, so changelogLockWaitTimeInMinutes still bounds it and a wedged holder fails a start, never hangs it.
public class AdvisoryLockService extends StandardLockService {

	public static final long KEY = 0x657a656562697400L;

	private static final String TRY_LOCK = "SELECT pg_try_advisory_lock(?)";

	private static final String UNLOCK = "SELECT pg_advisory_unlock(?)";

	private static final String HOLDERS = """
			SELECT a.pid, a.backend_start, a.application_name FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
			WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1 AND l.classid::bigint = ? AND l.objid::bigint = ?""";

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
		if (!held && tryLock()) {
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
			if (!unlock()) {
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
		try (PreparedStatement statement = connection().prepareStatement(HOLDERS)) {
			statement.setLong(1, KEY >>> 32);
			statement.setLong(2, KEY & 0xffffffffL);
			List<DatabaseChangeLogLock> holders = new ArrayList<>();
			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					holders.add(new DatabaseChangeLogLock(1, result.getTimestamp(2),
							"pid %d (%s)".formatted(result.getInt(1), result.getString(3))));
				}
			}
			return holders.toArray(new DatabaseChangeLogLock[0]);
		}
		catch (SQLException failure) {
			throw new LockException(failure);
		}
		finally {
			rollback();
		}
	}

	private boolean tryLock() throws LockException {
		try (PreparedStatement statement = connection().prepareStatement(TRY_LOCK)) {
			return keyed(statement);
		}
		catch (SQLException failure) {
			throw new LockException(failure);
		}
		finally {
			rollback();
		}
	}

	private boolean unlock() throws LockException {
		try (PreparedStatement statement = connection().prepareStatement(UNLOCK)) {
			return keyed(statement);
		}
		catch (SQLException failure) {
			throw new LockException(failure);
		}
		finally {
			rollback();
		}
	}

	private static boolean keyed(final PreparedStatement statement) throws SQLException {
		statement.setLong(1, KEY);
		try (ResultSet result = statement.executeQuery()) {
			return result.next() && result.getBoolean(1);
		}
	}

	private Connection connection() {
		return ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
	}

	// A session lock survives a rollback; ending the read leaves no idle transaction open across the wait.
	private void rollback() {
		try {
			database.rollback();
		}
		catch (DatabaseException failure) {
			Scope.getCurrentScope().getLog(getClass()).fine("rollback after an advisory lock call failed", failure);
		}
	}

}
