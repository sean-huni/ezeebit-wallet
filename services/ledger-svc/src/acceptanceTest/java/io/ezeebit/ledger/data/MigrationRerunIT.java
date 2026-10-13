package io.ezeebit.ledger.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.ezeebit.ledger.TestcontainersConfiguration;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

// The raw-SQL changesets meet a schema that already has their objects but no history row for them.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("acceptance")
class MigrationRerunIT {

	private static final List<String> RAW_SQL_CHANGESETS = List.of("001-currency-checks", "002-account-platform-unique",
			"002-account-checks", "003-journal-checks", "004-entry-checks", "005-account-type-fx",
			"005-journal-checks-conversion", "005-account-seed-fx", "006-journal-reason-column", "006-journal-status-refused");

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SpringLiquibase liquibase;

	@Test
	void rawSqlChangesetsAreMarkedRanOnASchemaThatAlreadyHasTheirObjects() throws Exception {
		jdbc.sql("DELETE FROM databasechangelog WHERE id IN (:ids)").param("ids", RAW_SQL_CHANGESETS).update();

		liquibase.afterPropertiesSet();

		assertThat(jdbc.sql("SELECT id || ':' || exectype FROM databasechangelog WHERE id IN (:ids) ORDER BY id")
				.param("ids", RAW_SQL_CHANGESETS).query(String.class).list())
				.containsExactly("001-currency-checks:MARK_RAN", "002-account-checks:MARK_RAN",
						"002-account-platform-unique:MARK_RAN", "003-journal-checks:MARK_RAN", "004-entry-checks:MARK_RAN",
						"005-account-seed-fx:MARK_RAN", "005-account-type-fx:MARK_RAN", "005-journal-checks-conversion:MARK_RAN",
						"006-journal-reason-column:MARK_RAN", "006-journal-status-refused:MARK_RAN");
	}

}
