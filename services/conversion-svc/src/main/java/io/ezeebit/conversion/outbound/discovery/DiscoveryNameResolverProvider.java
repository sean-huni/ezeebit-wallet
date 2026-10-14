package io.ezeebit.conversion.outbound.discovery;

import java.net.URI;

import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

// gRPC has no Eureka resolver of its own; "discovery:///ledger-svc" reads every instance's grpcPort from the registry.
@Component
public class DiscoveryNameResolverProvider extends NameResolverProvider {

	static final String SCHEME = "discovery";

	private final DiscoveryClient discovery;

	public DiscoveryNameResolverProvider(final DiscoveryClient discovery) {
		this.discovery = discovery;
		NameResolverRegistry.getDefaultRegistry().register(this);
	}

	@Override
	public NameResolver newNameResolver(final URI target, final NameResolver.Args args) {
		return SCHEME.equals(target.getScheme())
				? new DiscoveryNameResolver(discovery, target.getPath().substring(1), args) : null;
	}

	@Override
	public String getDefaultScheme() {
		return SCHEME;
	}

	@Override
	protected boolean isAvailable() {
		return true;
	}

	@Override
	protected int priority() {
		return 6;
	}

}
