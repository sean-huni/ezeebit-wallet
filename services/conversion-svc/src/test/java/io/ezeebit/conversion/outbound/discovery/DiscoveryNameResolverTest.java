package io.ezeebit.conversion.outbound.discovery;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.simple.InstanceProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;

class DiscoveryNameResolverTest {

	@Test
	void callsSpreadOverEveryInstanceAndSurviveOneStopping() throws Exception {
		AtomicInteger a = new AtomicInteger();
		AtomicInteger b = new AtomicInteger();
		Server one = ServerBuilder.forPort(0).addService(counting(a)).build().start();
		Server two = ServerBuilder.forPort(0).addService(counting(b)).build().start();
		new DiscoveryNameResolverProvider(discovery(one.getPort(), two.getPort()));
		ManagedChannel channel = ManagedChannelBuilder.forTarget("discovery:///ledger-svc").usePlaintext()
				.defaultLoadBalancingPolicy("round_robin").build();
		ConversionPostingsGrpc.ConversionPostingsBlockingStub stub = ConversionPostingsGrpc.newBlockingStub(channel);

		for (int i = 0; i < 20; i++) {
			stub.post(PostingRequest.getDefaultInstance());
		}
		assertThat(a.get()).as("instance one").isPositive();
		assertThat(b.get()).as("instance two").isPositive();

		// Graceful stop models a rolling restart (SIGTERM); abrupt kill: LedgerGuardTest, Task 8.
		one.shutdown().awaitTermination(5, SECONDS);
		int before = b.get();
		for (int i = 0; i < 5; i++) {
			stub.post(PostingRequest.getDefaultInstance());
		}
		assertThat(b.get() - before).as("calls served after one instance stopped").isEqualTo(5);
		channel.shutdownNow();
		two.shutdownNow();
	}

	private static ConversionPostingsGrpc.ConversionPostingsImplBase counting(final AtomicInteger calls) {
		return new ConversionPostingsGrpc.ConversionPostingsImplBase() {
			@Override
			public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
				calls.incrementAndGet();
				reply.onNext(PostingResult.getDefaultInstance());
				reply.onCompleted();
			}
		};
	}

	private static SimpleDiscoveryClient discovery(final int portOne, final int portTwo) {
		SimpleDiscoveryProperties properties = new SimpleDiscoveryProperties();
		properties.setInstances(Map.of("ledger-svc", List.of(instance(portOne), instance(portTwo))));
		return new SimpleDiscoveryClient(properties);
	}

	private static InstanceProperties instance(final int grpcPort) {
		InstanceProperties instance = new InstanceProperties();
		instance.setInstanceId("ledger-%d".formatted(grpcPort));
		instance.setServiceId("ledger-svc");
		instance.setHost("127.0.0.1");
		instance.setPort(1);
		instance.setMetadata(Map.of("grpcPort", String.valueOf(grpcPort)));
		return instance;
	}

}
