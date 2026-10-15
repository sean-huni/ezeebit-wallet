package io.ezeebit.conversion.outbound.discovery;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

// Re-reads the registry on every refresh, which gRPC requests after a connection fails.
final class DiscoveryNameResolver extends NameResolver {

	private static final String GRPC_PORT = "grpcPort";

	private static final int MAX_PORT = 65_535;

	private final DiscoveryClient discovery;
	private final String service;
	private final Args args;
	private Listener2 listener;

	DiscoveryNameResolver(final DiscoveryClient discovery, final String service, final Args args) {
		this.discovery = discovery;
		this.service = service;
		this.args = args;
	}

	@Override
	public String getServiceAuthority() {
		return service;
	}

	@Override
	public void start(final Listener2 listener) {
		this.listener = listener;
		resolve();
	}

	@Override
	public void refresh() {
		args.getSynchronizationContext().execute(this::resolve);
	}

	@Override
	public void shutdown() {
		listener = null;
	}

	private void resolve() {
		if (listener == null) {
			return;
		}
		StatusOr<List<EquivalentAddressGroup>> result;
		try {
			List<EquivalentAddressGroup> addresses = discovery.getInstances(service).stream()
					.flatMap(i -> address(i).stream()).toList();
			result = addresses.isEmpty()
					? StatusOr.fromStatus(
							Status.UNAVAILABLE.withDescription("no %s instance with a grpcPort".formatted(service)))
					: StatusOr.fromValue(addresses);
		}
		// An exception escaping the synchronization context panics the channel for good; an error status is retried.
		catch (RuntimeException e) {
			result = StatusOr.fromStatus(Status.UNAVAILABLE
					.withDescription("registry lookup for %s failed".formatted(service)).withCause(e));
		}
		// onResult2 is the synchronization-context form; start and refresh both run there.
		listener.onResult2(ResolutionResult.newBuilder().setAddressesOrError(result).build());
	}

	private static Optional<EquivalentAddressGroup> address(final ServiceInstance instance) {
		Map<String, String> metadata = instance.getMetadata();
		return Optional.ofNullable(metadata).map(m -> m.get(GRPC_PORT)).flatMap(DiscoveryNameResolver::port)
				.map(port -> new EquivalentAddressGroup(new InetSocketAddress(instance.getHost(), port)));
	}

	private static Optional<Integer> port(final String grpcPort) {
		try {
			return Optional.of(Integer.parseInt(grpcPort)).filter(p -> p > 0 && p <= MAX_PORT);
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

}
