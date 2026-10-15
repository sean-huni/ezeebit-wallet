package io.ezeebit.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

// A misspelt keepalive key binds nothing and fails nothing, and clients would then never re-resolve the registry.
class GrpcServerConnectionAgeTest {

	@Test
	void connectionsAgeOutSoClientsReResolveAndFindNewReplicas() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		for (PropertySource<?> source : new YamlPropertySourceLoader().load("application",
				new ClassPathResource("application.yml"))) {
			environment.getPropertySources().addLast(source);
		}
		GrpcServerProperties.Keepalive.Connection connection = Binder.get(environment)
				.bind("spring.grpc.server", GrpcServerProperties.class).get().getKeepalive().getConnection();
		assertThat(connection.getMaxAge()).isEqualTo(Duration.ofSeconds(30));
		assertThat(connection.getGracePeriod()).isEqualTo(Duration.ofSeconds(5));
	}

}
