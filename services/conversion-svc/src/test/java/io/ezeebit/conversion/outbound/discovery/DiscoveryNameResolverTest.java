package io.ezeebit.conversion.outbound.discovery;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.simple.InstanceProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;

class DiscoveryNameResolverTest {

	private static final String TARGET = "discovery:///ledger-svc";

	@Test
	void callsSpreadOverEveryInstanceAndSurviveOneStopping() throws Exception {
		AtomicInteger a = new AtomicInteger();
		AtomicInteger b = new AtomicInteger();
		Server one = server(a);
		Server two = server(b);
		DiscoveryNameResolverProvider provider = new DiscoveryNameResolverProvider(
				discovery(port(one.getPort()), port(two.getPort())));
		ManagedChannel channel = channel(TARGET);
		try {
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
		}
		finally {
			close(provider, channel, one, two);
		}
	}

	@Test
	void aFailingRegistryFailsCallsUnavailableAndDoesNotPanicTheChannel() {
		DiscoveryNameResolverProvider provider = new DiscoveryNameResolverProvider(throwing());
		ManagedChannel channel = channel(TARGET);
		try {
			ConversionPostingsGrpc.ConversionPostingsBlockingStub stub = ConversionPostingsGrpc.newBlockingStub(channel);
			for (int i = 0; i < 2; i++) {
				final int call = i;
				assertThatThrownBy(() -> stub.post(PostingRequest.getDefaultInstance()))
						.isInstanceOfSatisfying(StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode())
								.as("call %d".formatted(call)).isEqualTo(Status.Code.UNAVAILABLE));
			}
		}
		finally {
			close(provider, channel);
		}
	}

	@Test
	void anInstanceWithAnUnparseableGrpcPortIsSkipped() throws Exception {
		AtomicInteger live = new AtomicInteger();
		Server server = server(live);
		DiscoveryNameResolverProvider provider = new DiscoveryNameResolverProvider(
				discovery(instance("ledger-bad", "x"), port(server.getPort())));
		ManagedChannel channel = channel(TARGET);
		try {
			ConversionPostingsGrpc.ConversionPostingsBlockingStub stub = ConversionPostingsGrpc.newBlockingStub(channel);
			for (int i = 0; i < 5; i++) {
				stub.post(PostingRequest.getDefaultInstance());
			}
			assertThat(live.get()).isEqualTo(5);
		}
		finally {
			close(provider, channel, server);
		}
	}

	@Test
	void aBareHostAndPortTargetStillResolvesThroughDns() throws Exception {
		AtomicInteger direct = new AtomicInteger();
		Server server = server(direct);
		DiscoveryNameResolverProvider provider = new DiscoveryNameResolverProvider(discovery());
		ManagedChannel channel = channel("127.0.0.1:%d".formatted(server.getPort()));
		try {
			ConversionPostingsGrpc.newBlockingStub(channel).post(PostingRequest.getDefaultInstance());
			assertThat(direct.get()).isEqualTo(1);
		}
		finally {
			close(provider, channel, server);
		}
	}

	@Test
	void aDestroyedProviderNoLongerAnswersForTheScheme() throws Exception {
		AtomicInteger live = new AtomicInteger();
		Server server = server(live);
		DiscoveryNameResolverProvider stale = new DiscoveryNameResolverProvider(discovery(port(1)));
		stale.destroy();
		DiscoveryNameResolverProvider current = new DiscoveryNameResolverProvider(
				discovery(port(server.getPort())));
		ManagedChannel channel = channel(TARGET);
		try {
			ConversionPostingsGrpc.newBlockingStub(channel).post(PostingRequest.getDefaultInstance());
			assertThat(live.get()).isEqualTo(1);
		}
		finally {
			close(current, channel, server);
		}
	}

	@Test
	void aTargetWithoutAServiceNameIsRejectedByName() throws Exception {
		DiscoveryNameResolverProvider provider = new DiscoveryNameResolverProvider(discovery());
		try {
			for (String target : List.of("discovery:ledger-svc", "discovery:///")) {
				assertThatThrownBy(() -> channel(target).shutdownNow()).isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining(target);
			}
		}
		finally {
			provider.destroy();
		}
	}

	private static ManagedChannel channel(final String target) {
		return ManagedChannelBuilder.forTarget(target).usePlaintext().defaultLoadBalancingPolicy("round_robin")
				.build();
	}

	private static Server server(final AtomicInteger calls) throws Exception {
		return ServerBuilder.forPort(0).addService(counting(calls)).build().start();
	}

	private static void close(final DiscoveryNameResolverProvider provider, final ManagedChannel channel,
			final Server... servers) {
		provider.destroy();
		channel.shutdownNow();
		for (Server server : servers) {
			server.shutdownNow();
		}
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

	private static SimpleDiscoveryClient discovery(final InstanceProperties... instances) {
		SimpleDiscoveryProperties properties = new SimpleDiscoveryProperties();
		properties.setInstances(Map.of("ledger-svc", List.of(instances)));
		return new SimpleDiscoveryClient(properties);
	}

	private static InstanceProperties port(final int grpcPort) {
		return instance("ledger-%d".formatted(grpcPort), String.valueOf(grpcPort));
	}

	private static InstanceProperties instance(final String id, final String grpcPort) {
		InstanceProperties instance = new InstanceProperties();
		instance.setInstanceId(id);
		instance.setServiceId("ledger-svc");
		instance.setHost("127.0.0.1");
		instance.setPort(1);
		instance.setMetadata(Map.of("grpcPort", grpcPort));
		return instance;
	}

	private static DiscoveryClient throwing() {
		return new DiscoveryClient() {
			@Override
			public String description() {
				return "throws on every lookup";
			}

			@Override
			public List<ServiceInstance> getInstances(final String serviceId) {
				throw new IllegalStateException("registry unreachable");
			}

			@Override
			public List<String> getServices() {
				return List.of();
			}
		};
	}

}
