package io.ezeebit.gateway.config.properties;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// Values live only in the served gateway-svc.yml; a missing or non-positive one fails the boot, not a request.
@Validated
@ConfigurationProperties("ezeebit.gateway.rate-limit")
public record RateLimitProperties(@Positive long capacity, @Positive long refillTokens, @NotNull Duration refillPeriod,
		@NotNull @Valid Store store) {

	public record Store(@NotBlank String host, @Positive int port, @NotBlank String password, @NotNull Duration timeout) {

		// The generated toString would print the password into any log line that formats the properties.
		@Override
		public String toString() {
			return "Store[host=%s, port=%d, password=****, timeout=%s]".formatted(host, port, timeout);
		}

	}

}
