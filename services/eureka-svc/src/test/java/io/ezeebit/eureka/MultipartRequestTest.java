package io.ezeebit.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

// The DAST class sweep (2026-11-05): a multipart Content-Type without a boundary failed the multipart parse as a 500.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultipartRequestTest {

	@LocalServerPort
	private int port;

	@Test
	void aMultipartContentTypeIsIgnored() {
		int status = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { }).build()
				.get().uri("http://localhost:%d/actuator/health".formatted(port)).header("Content-Type", "multipart/form-data")
				.retrieve().toBodilessEntity().getStatusCode().value();

		assertThat(status).isEqualTo(200);
	}

}
