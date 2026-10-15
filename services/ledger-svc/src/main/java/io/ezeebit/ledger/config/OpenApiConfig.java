package io.ezeebit.ledger.config;

import java.util.List;

import io.ezeebit.ledger.api.doc.ProblemResponses;
import io.ezeebit.ledger.api.doc.PublishedPaths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Two specs: `public` (merchant routes, served to the gateway only) and `internal` (never routed, loopback only).
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static final String EDGE_ANSWERS = """
			Call it through the gateway: the server below is the gateway, and each path here is served there under /api.
			Every error is an RFC 9457 problem detail (application/problem+json) with a stable `code`.
			Besides the answers listed per operation, the gateway itself can answer any operation with \
			400 INVALID_MERCHANT_ID, 400 MALFORMED_PATH, 429 RATE_LIMITED (with Retry-After), \
			503 RATE_LIMITER_UNAVAILABLE (with Retry-After), 503 UPSTREAM_UNAVAILABLE and 504 UPSTREAM_OUTCOME_UNKNOWN.""";

	private static final String INTERNAL = """
			Service-to-service calls: deposits from the money-in rail. Conversion postings from conversion-svc \
			arrive over gRPC (ezeebit.ledger.v1 ConversionPostings and ConversionSettlement), not here. \
			Never routed by the gateway; the service listens on loopback.""";

	@Bean
	GroupedOpenApi publicApi(@Value("${ezeebit.openapi.public-url}") final String gateway, final MessageSource messages) {
		return group("public", "/*/merchants/**", messages, new Info().title("ledger-svc public API").version("v1")
				.description("Balances and the statement of a merchant. %s".formatted(EDGE_ANSWERS)),
				new Server().url(gateway).description("the gateway"));
	}

	@Bean
	GroupedOpenApi internalApi(final MessageSource messages) {
		return group("internal", "/internal/**", messages, new Info().title("ledger-svc internal API").version("v1")
				.description(INTERNAL),
				new Server().url("/").description("this service, called directly"));
	}

	private static GroupedOpenApi group(final String name, final String paths, final MessageSource messages, final Info info,
			final Server server) {
		return GroupedOpenApi.builder().group(name).pathsToMatch(paths)
				.addOpenApiCustomizer(new PublishedPaths(ApiVersionConfig.SUPPORTED_VERSION))
				.addOpenApiCustomizer(new ProblemResponses(messages))
				.addOpenApiCustomizer(openApi -> openApi.info(info).servers(List.of(server)))
				.build();
	}

}
