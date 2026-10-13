package io.ezeebit.conversion.config;

import java.util.List;

import io.ezeebit.conversion.api.doc.ProblemResponses;
import io.ezeebit.conversion.api.doc.PublishedPaths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// One spec, `public`, served to the gateway only: conversion-svc has no internal endpoints.
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	static final String EDGE_ANSWERS = """
			Call it through the gateway: the server below is the gateway, and each path here is served there under /api.
			Every error is an RFC 9457 problem detail (application/problem+json) with a stable `code`.
			Besides the answers listed per operation, the gateway itself can answer any operation with \
			400 INVALID_MERCHANT_ID, 400 MALFORMED_PATH, 429 RATE_LIMITED (with Retry-After), \
			503 RATE_LIMITER_UNAVAILABLE (with Retry-After), 503 UPSTREAM_UNAVAILABLE and 504 UPSTREAM_OUTCOME_UNKNOWN.""";

	@Bean
	GroupedOpenApi publicApi(@Value("${ezeebit.openapi.public-url}") final String gateway, final MessageSource messages) {
		Info info = new Info().title("conversion-svc public API").version("v1")
				.description("Quotes and conversions between a merchant's currencies. %s".formatted(EDGE_ANSWERS));
		Server server = new Server().url(gateway).description("the gateway");
		return GroupedOpenApi.builder().group("public").pathsToMatch("/*/merchants/**")
				.addOpenApiCustomizer(new PublishedPaths(ApiVersionConfig.SUPPORTED_VERSION))
				.addOpenApiCustomizer(new ProblemResponses(messages))
				.addOpenApiCustomizer(openApi -> openApi.info(info).servers(List.of(server)))
				.build();
	}

}
