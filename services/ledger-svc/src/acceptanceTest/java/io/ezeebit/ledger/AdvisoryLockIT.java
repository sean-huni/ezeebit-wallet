package io.ezeebit.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.ezeebit.persistence.AdvisoryLockService;
import liquibase.GlobalConfiguration;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LockException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.lockservice.DatabaseChangeLogLock;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

// A SIGKILLed migrator leaves the lock row set for ever; the advisory lock it held ends with its session instead.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("acceptance")
class AdvisoryLockIT {

	private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

	// Its precondition marks it ran on an existing schema; an up-to-date schema never takes the lock at all.
	private static final String PENDING_CHANGESET = "001-currency-checks";

	private static final long NO_WAIT = 0L;

	private static final long ONE_MINUTE = 1L;

	@Autowired
	private PostgreSQLContainer postgres;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	@Test
	void theFactoryChoosesTheAdvisoryLockForPostgres() throws Exception {
		try (Connection connection = connect()) {
			Database database = database(connection);

			assertThat(LockServiceFactory.getInstance().getLockService(database)).isInstanceOf(AdvisoryLockService.class);
		}
	}

	// With no spring.liquibase.url, as deployed, Spring migrates on a Hikari connection that goes back to the pool.
	@Test
	void theSpringMigrationGivesItsPooledConnectionBackWithoutTheLock() throws Exception {
		try (Connection holder = connect()) {
			lock(holder);
			assertThat(heldKeyLocks()).as("control: the probe sees a held key").isEqualTo(1);
			unlock(holder);
		}
		assertThat(dataSource).as("control: the pooled path is the one under test").isInstanceOf(HikariDataSource.class);
		SpringLiquibase pooled = new SpringLiquibase();
		pooled.setDataSource(dataSource);
		pooled.setChangeLog("classpath:%s".formatted(CHANGELOG));
		pooled.setResourceLoader(new DefaultResourceLoader());
		leaveOneChangesetPending();

		pooled.afterPropertiesSet();

		assertThat(pendingChangesetRecorded()).as("the run did work, so it took the lock").isTrue();
		assertThat(heldKeyLocks()).isZero();
	}

	@Test
	void aResetMidRunStillReleasesTheSessionLock() throws Exception {
		try (Connection connection = connect()) {
			LockService lockService = LockServiceFactory.getInstance().getLockService(database(connection));
			assertThat(lockService.acquireLock()).isTrue();

			lockService.reset();
			lockService.releaseLock();

			assertThat(heldKeyLocks()).isZero();
		}
	}

	@Test
	void aStaleLockRowFromAKilledMigratorDoesNotBlockTheNextStart() {
		jdbc.sql("UPDATE databasechangeloglock SET locked = TRUE, lockgranted = now(), lockedby = 'killed-pod' WHERE id = 1")
				.update();
		leaveOneChangesetPending();
		try {
			runLiquibase(NO_WAIT);

			assertThat(pendingChangesetRecorded()).isTrue();
		}
		finally {
			jdbc.sql("UPDATE databasechangeloglock SET locked = FALSE, lockgranted = NULL, lockedby = NULL WHERE id = 1").update();
		}
	}

	@Test
	void twoMigratorsAtOnceSerialiseOnTheAdvisoryLock() throws Exception {
		try (Connection holder = connect()) {
			lock(holder);
			leaveOneChangesetPending();
			CompletableFuture<Void> second = CompletableFuture.runAsync(() -> runLiquibase(ONE_MINUTE));
			Thread.sleep(1_500);
			assertThat(second).isNotDone();

			unlock(holder);

			second.get(30, TimeUnit.SECONDS);
		}
	}

	@Test
	void aLiveHolderIsNamedWhenTheWaitRunsOut() throws Exception {
		try (Connection holder = connect()) {
			lock(holder);
			leaveOneChangesetPending();

			assertThatThrownBy(() -> runLiquibase(NO_WAIT)).rootCause().isInstanceOf(LockException.class)
					.hasMessageContaining("Currently locked by pid %d".formatted(pidOf(holder)));
		}
	}

	@Test
	void theHolderListingNamesAHolderInThisDatabase() throws Exception {
		try (Connection holder = connect(); Connection reader = connect()) {
			lock(holder);
			try {
				DatabaseChangeLogLock[] holders = LockServiceFactory.getInstance().getLockService(database(reader)).listLocks();

				assertThat(holders).singleElement().satisfies(lock -> {
					assertThat(lock.getLockedBy()).startsWith("pid %d (".formatted(pidOf(holder)));
					assertThat(lock.getLockGranted()).isNotNull();
				});
			}
			finally {
				unlock(holder);
			}
		}
	}

	// Advisory locks are per database, so a holder of the same key elsewhere on the server blocks nothing here.
	@Test
	void theHolderListingIgnoresAHolderInAnotherDatabase() throws Exception {
		String other = "advisory_other_%d".formatted(System.nanoTime());
		jdbc.sql("CREATE DATABASE " + other).update();
		try {
			try (Connection elsewhere = connect(other); Connection reader = connect()) {
				lock(elsewhere);
				assertThat(heldKeyLocks()).as("control: the key is held on the server").isEqualTo(1);

				assertThat(LockServiceFactory.getInstance().getLockService(database(reader)).listLocks()).isEmpty();
			}
		}
		finally {
			jdbc.sql("DROP DATABASE IF EXISTS " + other).update();
		}
	}

	@Test
	void theLockIsReleasedWhenItsConnectionDies() throws Exception {
		Connection doomed = connect();
		lock(doomed);
		int pid = pidOf(doomed);
		try (Connection admin = connect(); Statement statement = admin.createStatement()) {
			statement.execute("SELECT pg_terminate_backend(%d)".formatted(pid));
		}
		leaveOneChangesetPending();

		runLiquibase(ONE_MINUTE);

		assertThat(heldKeyLocks()).isZero();
	}

	private void runLiquibase(final long waitMinutes) {
		try (Connection connection = connect()) {
			Database database = database(connection);
			Map<String, Object> scope = Map.of(GlobalConfiguration.CHANGELOGLOCK_WAIT_TIME.getKey(), waitMinutes,
					GlobalConfiguration.CHANGELOGLOCK_POLL_RATE.getKey(), 1L,
					Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor());
			Scope.child(scope, () -> {
				new CommandScope(UpdateCommandStep.COMMAND_NAME)
						.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
						.addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
						.execute();
			});
		}
		catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private void leaveOneChangesetPending() {
		jdbc.sql("DELETE FROM databasechangelog WHERE id = :id").param("id", PENDING_CHANGESET).update();
	}

	private boolean pendingChangesetRecorded() {
		return jdbc.sql("SELECT count(*) FROM databasechangelog WHERE id = :id").param("id", PENDING_CHANGESET)
				.query(Integer.class).single() == 1;
	}

	private int heldKeyLocks() {
		return jdbc.sql("""
				SELECT count(*) FROM pg_locks
				WHERE locktype = 'advisory' AND granted AND objsubid = 1
				AND ((classid::bigint << 32) | objid::bigint) = :key""")
				.param("key", AdvisoryLockService.KEY).query(Integer.class).single();
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private Connection connect(final String databaseName) throws SQLException {
		String url = postgres.getJdbcUrl().replaceFirst("/%s(\\?|$)".formatted(postgres.getDatabaseName()), "/%s$1".formatted(databaseName));
		return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
	}

	private static Database database(final Connection connection) throws Exception {
		return DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
	}

	private static void lock(final Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("SELECT pg_advisory_lock(%d)".formatted(AdvisoryLockService.KEY));
		}
	}

	private static void unlock(final Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("SELECT pg_advisory_unlock(%d)".formatted(AdvisoryLockService.KEY));
		}
	}

	private static int pidOf(final Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
			result.next();
			return result.getInt(1);
		}
	}

}
