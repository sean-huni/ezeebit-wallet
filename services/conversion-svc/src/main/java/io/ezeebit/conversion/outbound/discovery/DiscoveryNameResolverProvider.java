package io.ezeebit.conversion.outbound.discovery;

import java.net.URI;

import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

// gRPC has no Eureka resolver of its own; "discovery:///ledger-svc" reads every instance's grpcPort from the registry.
@Component
public class DiscoveryNameResolverProvider extends NameResolverProvider implements DisposableBean {

	static final String SCHEME = "discovery";

	private final DiscoveryClient discovery;

	public DiscoveryNameResolverProvider(final DiscoveryClient discovery) {
		this.discovery = discovery;
		NameResolverRegistry.getDefaultRegistry().register(this);
	}

	@Override
	public NameResolver newNameResolver(final URI target, final NameResolver.Args args) {
		if (!SCHEME.equals(target.getScheme())) {
			return null;
		}
		String path = target.getPath();
		if (path == null || path.length() < 2 || path.charAt(0) != '/') {
			throw new IllegalArgumentException("%s needs a service name: discovery:///<service>".formatted(target));
		}
		return new DiscoveryNameResolver(discovery, path.substring(1), args);
	}

	@Override
	public String getDefaultScheme() {
		return SCHEME;
	}

	@Override
	protected boolean isAvailable() {
		return true;
	}

	// DNS is 5 and wins ties by loading first; above 5 every bare host:port target would resolve through Eureka.
	@Override
	protected int priority() {
		return 5;
	}

	// The registry is JVM-global and keeps the first provider at equal priority, so a closed context must leave it.
	@Override
	public void destroy() {
		NameResolverRegistry.getDefaultRegistry().deregister(this);
	}

}
