package io.ezeebit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

// This service does not import the served config, so its own file must match the fleet's shutdown budget.
class OwnShutdownSettingsTest {

	@Test
	void shutsDownGracefullyWithinTwentySecondsPerPhase() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		Properties props = yaml.getObject();
		assertThat(props).containsEntry("server.shutdown", "graceful")
				.containsEntry("spring.lifecycle.timeout-per-shutdown-phase", "20s");
	}

}
