package io.ezeebit.conversion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.conversion.outbound.discovery.DiscoveryNameResolverProvider;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.grpc.client.autoconfigure.CompositeChannelFactoryAutoConfiguration;
import org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.simple.InstanceProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;

// Builds ledgerChannel through Boot's real GrpcChannelFactory: a builder option it rejects only fails at boot.
class LedgerChannelTest {

	private Server ledger;

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class,
					GrpcClientAutoConfiguration.class, CompositeChannelFactoryAutoConfiguration.class))
			.withUserConfiguration(ClientConfig.class)
			.withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP)
			.withBean(DiscoveryNameResolverProvider.class)
			.withPropertyValues("conversion.ledger.target=discovery:///ledger-svc",
					"conversion.rates.url=http://127.0.0.1:1", "conversion.rates.connect-timeout=1s",
					"conversion.rates.read-timeout=1s", "conversion.rates.max-age=10s",
					"conversion.rates.max-jump-percent=5", "conversion.rates.jump-window=5m");

	@BeforeEach
	void start() throws Exception {
		ledger = ServerBuilder.forPort(0).addService(new ConversionPostingsGrpc.ConversionPostingsImplBase() {

			@Override
			public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
				reply.onNext(PostingResult.newBuilder().setStatus(PostingStatus.POSTED).build());
				reply.onCompleted();
			}

		}).build().start();
	}

	@AfterEach
	void stop() throws Exception {
		ledger.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void theLedgerChannelBootsAndReachesALedgerTheRegistryNames() {
		context.withBean(DiscoveryClient.class, () -> discovery(ledger.getPort())).run(app -> {
			assertThat(app).hasNotFailed().hasBean("ledgerChannel");

			PostingResult result = ConversionPostingsGrpc.newBlockingStub(app.getBean("ledgerChannel", ManagedChannel.class))
					.withDeadlineAfter(5, TimeUnit.SECONDS).post(PostingRequest.getDefaultInstance());

			assertThat(result.getStatus()).isEqualTo(PostingStatus.POSTED);
		});
	}

	private static SimpleDiscoveryClient discovery(final int grpcPort) {
		InstanceProperties instance = new InstanceProperties();
		instance.setInstanceId("ledger-1");
		instance.setServiceId("ledger-svc");
		instance.setHost("127.0.0.1");
		instance.setPort(1);
		instance.setMetadata(Map.of("grpcPort", String.valueOf(grpcPort)));
		SimpleDiscoveryProperties properties = new SimpleDiscoveryProperties();
		properties.setInstances(Map.of("ledger-svc", List.of(instance)));
		return new SimpleDiscoveryClient(properties);
	}

}
