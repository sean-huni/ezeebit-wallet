package io.ezeebit.gateway;

import static io.ezeebit.gateway.Gateways.post;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

// A registry keeps a killed pod's address for tens of seconds. The served merchant routes must skip it when, and only
// when, the request never reached a service: a refused or timed-out connect. A request that was sent is never repeated.
class DeadInstanceRetryIT {

	private static final String BODY = "{\"amount\":\"10.00\"}";

	// TEST-NET-1 is unroutable: a SYN to it is never answered, as with a deleted pod's IP on some CNIs.
	private static final String BLACK_HOLE = "http://192.0.2.1:9";

	private static RedisStore store;
	private static UpstreamStub live;
	private static UpstreamStub slow;
	private static ConfigurableApplicationContext gateway;
	private static ConfigurableApplicationContext slowGateway;
	private static ConfigurableApplicationContext deadGateway;

	@BeforeAll
	static void start() {
		store = RedisStore.started();
		live = UpstreamStub.start(201, Duration.ZERO);
		slow = UpstreamStub.start(201, Duration.ofSeconds(2));
		gateway = Gateways.start(store.args(), List.of("--eureka.client.enabled=false",
				"--spring.cloud.discovery.client.simple.instances.conversion-svc[0].uri=%s".formatted(refusedUri()),
				"--spring.cloud.discovery.client.simple.instances.conversion-svc[1].uri=%s".formatted(live.uri()),
				"--spring.cloud.discovery.client.simple.instances.ledger-svc[0].uri=%s".formatted(BLACK_HOLE),
				"--spring.cloud.discovery.client.simple.instances.ledger-svc[1].uri=%s".formatted(live.uri())));
		slowGateway = Gateways.start(store.args(), List.of("--eureka.client.enabled=false",
				"--spring.http.clients.read-timeout=500ms",
				"--spring.cloud.discovery.client.simple.instances.conversion-svc[0].uri=%s".formatted(slow.uri())));
		deadGateway = Gateways.start(store.args(), List.of("--eureka.client.enabled=false",
				"--spring.cloud.discovery.client.simple.instances.conversion-svc[0].uri=%s".formatted(refusedUri()),
				"--spring.cloud.discovery.client.simple.instances.ledger-svc[0].uri=%s".formatted(BLACK_HOLE)));
	}

	@AfterAll
	static void stop() {
		deadGateway.close();
		slowGateway.close();
		gateway.close();
		slow.close();
		live.close();
		store.stop();
	}

	@Test
	void aRefusedInstanceIsSkippedAndEveryPostArrivesOnce() {
		int before = live.hits();
		double resent = resends(gateway, "conversion");
		String merchant = UUID.randomUUID().toString();

		for (int i = 0; i < 20; i++) {
			ResponseEntity<String> answer = post(gateway, "/api/v1/merchants/%s/quotes".formatted(merchant), BODY);
			assertThat(answer.getStatusCode().value()).as("request %d: %s", i, answer.getBody()).isEqualTo(201);
		}

		assertThat(live.hits() - before).isEqualTo(20);
		assertThat(live.seen().subList(before, live.hits())).allSatisfy(seen -> assertThat(seen.body()).isEqualTo(BODY));
		// Round-robin over one dead and one live instance: a resent request moves the pointer by two, so every request
		// after the first lands on the dead one first. 19 or 20 resends, by where the pointer started.
		assertThat(resends(gateway, "conversion") - resent).isBetween(19.0, 20.0);
	}

	@Test
	void everyResendIsCountedOnItsRoute() {
		double conversion = resends(deadGateway, "conversion");
		double ledger = resends(deadGateway, "ledger");

		for (int i = 0; i < 5; i++) {
			ResponseEntity<String> answer = post(deadGateway, "/api/v1/merchants/%s/quotes".formatted(UUID.randomUUID()), BODY);
			assertThat(answer.getStatusCode().value()).as(answer.getBody()).isEqualTo(503);
		}

		// Three attempts per request against a pool with no live instance: two resends each.
		assertThat(resends(deadGateway, "conversion") - conversion).isEqualTo(10);
		assertThat(resends(deadGateway, "ledger") - ledger).isZero();
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	void aPoolThatNeverAcceptsAnswers503BecauseNothingWasSent() {
		ResponseEntity<String> answer = post(deadGateway, "/api/v1/merchants/%s/entries".formatted(UUID.randomUUID()), BODY);

		assertThat(answer.getStatusCode().value()).as(answer.getBody()).isEqualTo(503);
		assertThat(Gateways.code(answer)).isEqualTo("UPSTREAM_UNAVAILABLE");
	}

	@Test
	void everyMerchantRouteCountsResendsFromZero() {
		assertThat(resends(slowGateway, "ledger")).isZero();
		assertThat(resends(slowGateway, "conversion")).isZero();
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void anInstanceThatNeverAcceptsIsSkippedAndEveryPostArrivesOnce() {
		int before = live.hits();
		String merchant = UUID.randomUUID().toString();

		for (int i = 0; i < 10; i++) {
			ResponseEntity<String> answer = post(gateway, "/api/v1/merchants/%s/entries".formatted(merchant), BODY);
			assertThat(answer.getStatusCode().value()).as("request %d: %s", i, answer.getBody()).isEqualTo(201);
		}

		assertThat(live.hits() - before).isEqualTo(10);
		assertThat(live.seen().subList(before, live.hits())).allSatisfy(seen -> assertThat(seen.body()).isEqualTo(BODY));
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void aRequestThatReachedAnInstanceIsNeverRepeated() {
		ResponseEntity<String> answer = post(slowGateway, "/api/v1/merchants/%s/conversions".formatted(UUID.randomUUID()), BODY);

		assertThat(answer.getStatusCode().value()).as(answer.getBody()).isEqualTo(504);
		assertThat(Gateways.code(answer)).isEqualTo("UPSTREAM_OUTCOME_UNKNOWN");
		assertThat(slow.hits()).isEqualTo(1);
	}

	private static double resends(final ConfigurableApplicationContext context, final String route) {
		Counter counter = context.getBean(MeterRegistry.class).find("ezeebit.gateway.upstream.resend").tag("route", route).counter();
		assertThat(counter).as("resend counter for route %s", route).isNotNull();
		return counter.count();
	}

	// A port that was bound and released: nothing listens, so a connect is refused at once.
	private static String refusedUri() {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return "http://127.0.0.1:%d".formatted(socket.getLocalPort());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
