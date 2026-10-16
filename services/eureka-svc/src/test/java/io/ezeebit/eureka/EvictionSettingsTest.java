package io.ezeebit.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

// Self-preservation reads a five-service fleet losing one pod as a partition and stops evicting.
class EvictionSettingsTest {

	@Test
	void evictsEveryFiveSecondsWithoutSelfPreservation() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		Properties props = yaml.getObject();
		assertThat(props).containsEntry("eureka.server.enable-self-preservation", false)
				.containsEntry("eureka.server.eviction-interval-timer-in-ms", 5000);
	}

}
