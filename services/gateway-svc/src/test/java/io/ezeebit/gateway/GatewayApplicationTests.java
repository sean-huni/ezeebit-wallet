package io.ezeebit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.cloud.gateway.server.webmvc.routes[0].id=ledger",
		"spring.cloud.gateway.server.webmvc.routes[0].uri=lb://ledger-svc",
		"spring.cloud.gateway.server.webmvc.routes[0].predicates[0]=Path=/api/v1/merchants/*/balances/**",
		"spring.cloud.gateway.server.webmvc.routes[1].id=dead",
		"spring.cloud.gateway.server.webmvc.routes[1].uri=http://localhost:1",
		"spring.cloud.gateway.server.webmvc.routes[1].predicates[0]=Path=/api/v1/merchants/*/quotes/**" })
class GatewayApplicationTests {

	@LocalServerPort
	private int port;

	@Test
	void unregisteredUpstreamAnswers503() {
		assertThat(statusOf("/api/v1/merchants/m1/balances")).isEqualTo(503);
	}

	@Test
	void unreachableUpstreamAnswers503() {
		assertThat(statusOf("/api/v1/merchants/m1/quotes")).isEqualTo(503);
	}

	private int statusOf(final String uri) {
		HttpStatusCode status = RestClient.create("http://localhost:" + port).get()
				.uri(uri)
				.exchange((request, response) -> response.getStatusCode());
		return status.value();
	}

}
