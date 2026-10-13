package io.ezeebit.conversion.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.ezeebit.conversion.TestcontainersConfiguration;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

// The raw-SQL changesets meet a schema that already has their objects but no history row for them.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "conversion.sweep-interval=1h")
@ActiveProfiles("acceptance")
class MigrationRerunIT {

	private static final List<String> RAW_SQL_CHANGESETS = List.of("001-quote-checks", "002-conversion-checks");

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
				.containsExactly("001-quote-checks:MARK_RAN", "002-conversion-checks:MARK_RAN");
	}

}
