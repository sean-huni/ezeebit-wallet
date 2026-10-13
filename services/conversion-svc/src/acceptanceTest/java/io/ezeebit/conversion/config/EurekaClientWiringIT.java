package io.ezeebit.conversion.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.ezeebit.conversion.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Every other Spring test runs with Eureka off. With it on, the registry must be fetched over plain HTTP:
// a load-balanced RestClient.Builder bean once captured Eureka's own client and it never reached the registry.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "conversion.sweep-interval=1h", "eureka.client.enabled=true",
		"eureka.client.register-with-eureka=false", "eureka.client.fetch-registry=true" })
@ActiveProfiles("acceptance")
class EurekaClientWiringIT {

	private static final WireMockServer REGISTRY = started();

	@DynamicPropertySource
	static void registry(final DynamicPropertyRegistry registry) {
		registry.add("eureka.client.service-url.defaultZone", () -> REGISTRY.baseUrl() + "/eureka/");
	}

	@AfterAll
	static void stop() {
		REGISTRY.stop();
	}

	@Test
	void theRegistryIsFetchedDirectlyNotThroughTheLoadBalancer() {
		assertThat(REGISTRY.findAll(getRequestedFor(urlPathMatching("/eureka/apps/?")))).isNotEmpty();
	}

	private static WireMockServer started() {
		WireMockServer server = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1"));
		server.start();
		server.stubFor(get(urlPathMatching("/eureka/apps/?")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"applications\":{\"versions__delta\":\"1\",\"apps__hashcode\":\"\",\"application\":[]}}")));
		return server;
	}

}
