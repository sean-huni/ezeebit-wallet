package io.ezeebit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigApplicationTests {

	@LocalServerPort
	private int port;

	@Test
	void servesLedgerConfig() {
		String body = RestClient.create("http://localhost:" + port).get()
				.uri("/ledger-svc/default").retrieve().body(String.class);

		assertThat(body).contains("jdbc:postgresql://localhost:${POSTGRES_PORT:5432}/ledger");
	}

}
