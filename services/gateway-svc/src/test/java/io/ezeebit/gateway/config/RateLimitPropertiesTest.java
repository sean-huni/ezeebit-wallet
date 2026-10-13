package io.ezeebit.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.ezeebit.gateway.config.properties.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

// Binds the SERVED gateway-svc.yml, the one home of the limits, as the running gateway does.
class RateLimitPropertiesTest {

	private static final String PREFIX = "ezeebit.gateway.rate-limit";

	@Test
	void theServedFileBindsAPaydayBurstAndAShortStoreTimeout() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		for (PropertySource<?> source : new YamlPropertySourceLoader().load("served", new FileSystemResource(servedFile()))) {
			environment.getPropertySources().addLast(source);
		}

		RateLimitProperties limits = Binder.get(environment).bind(PREFIX, RateLimitProperties.class).get();

		assertThat(limits.capacity()).isEqualTo(100);
		assertThat(limits.refillTokens()).isEqualTo(20);
		assertThat(limits.refillPeriod()).isEqualTo(Duration.ofSeconds(1));
		assertThat(limits.store().host()).isEqualTo("localhost");
		assertThat(limits.store().port()).isEqualTo(6379);
		assertThat(limits.store().timeout()).isEqualTo(Duration.ofMillis(250));
	}

	@Test
	void aZeroCapacityFailsTheBind() {
		Map<String, String> values = Map.of("capacity", "0", "refill-tokens", "1", "refill-period", "1s", "store.host", "h",
				"store.port", "1", "store.password", "p", "store.timeout", "1s").entrySet().stream()
				.collect(Collectors.toMap(entry -> "%s.%s".formatted(PREFIX, entry.getKey()), Map.Entry::getValue));
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		Binder binder = new Binder(List.of(new MapConfigurationPropertySource(values)));

		assertThatThrownBy(() -> binder.bind(PREFIX, Bindable.of(RateLimitProperties.class), new ValidationBindHandler(validator)))
				.isInstanceOf(BindException.class).hasStackTraceContaining("capacity");
		validator.close();
	}

	@Test
	void thePasswordNeverReachesToString() {
		RateLimitProperties.Store store = new RateLimitProperties.Store("h", 1, "s3cret", Duration.ofMillis(1));

		assertThat(store.toString()).doesNotContain("s3cret").contains("****");
	}

	private static String servedFile() {
		String imports = System.getProperty("spring.config.import");
		return List.of(imports.split(",")).stream().filter(path -> path.endsWith("gateway-svc.yml")).findFirst()
				.orElseThrow().substring("file:".length());
	}

}
