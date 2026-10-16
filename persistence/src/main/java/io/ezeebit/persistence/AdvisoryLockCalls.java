package io.ezeebit.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.DatabaseChangeLogLock;

// The SQL behind AdvisoryLockService, each call ended by a rollback so no idle transaction spans the lock wait.
final class AdvisoryLockCalls {

	private static final String TRY_LOCK = "SELECT pg_try_advisory_lock(?)";

	private static final String UNLOCK = "SELECT pg_advisory_unlock(?)";

	// pg_locks spans the server, and an advisory key only conflicts within one database.
	private static final String HOLDERS = """
			SELECT a.pid, coalesce(a.backend_start, now()), a.application_name
			FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
			WHERE l.locktype = 'advisory' AND l.granted AND l.objsubid = 1 AND l.classid::bigint = ? AND l.objid::bigint = ?
			AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())""";

	private final Database database;

	private final long key;

	AdvisoryLockCalls(final Database database, final long key) {
		this.database = database;
		this.key = key;
	}

	boolean tryLock() throws LockException {
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

	boolean unlock() throws LockException {
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

	DatabaseChangeLogLock[] holders() throws LockException {
		try (PreparedStatement statement = connection().prepareStatement(HOLDERS)) {
			statement.setLong(1, key >>> 32);
			statement.setLong(2, key & 0xffffffffL);
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

	private boolean keyed(final PreparedStatement statement) throws SQLException {
		statement.setLong(1, key);
		try (ResultSet result = statement.executeQuery()) {
			return result.next() && result.getBoolean(1);
		}
	}

	private Connection connection() {
		return ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
	}

	// A session lock survives a rollback.
	private void rollback() {
		try {
			database.rollback();
		}
		catch (DatabaseException failure) {
			Scope.getCurrentScope().getLog(getClass()).fine("rollback after an advisory lock call failed", failure);
		}
	}

}
