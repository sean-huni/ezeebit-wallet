package io.ezeebit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

// A killed replica must leave every client's view within seconds, not the 90s default lease.
class ServedRegistryTimingTest {

	private static Properties served() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("served/application.yml"));
		return yaml.getObject();
	}

	@Test
	void clientsRenewEveryFiveSecondsAndExpireAfterFifteen() {
		assertThat(served()).containsEntry("eureka.instance.lease-renewal-interval-in-seconds", 5)
				.containsEntry("eureka.instance.lease-expiration-duration-in-seconds", 15);
	}

	@Test
	void everyServiceShutsDownGracefully() {
		assertThat(served()).containsEntry("server.shutdown", "graceful")
				.containsEntry("spring.lifecycle.timeout-per-shutdown-phase", "20s");
	}

}
