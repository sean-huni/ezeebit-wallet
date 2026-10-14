package io.ezeebit.conversion.acceptance;

import io.cucumber.spring.CucumberContextConfiguration;
import io.cucumber.spring.ScenarioScope;
import io.ezeebit.conversion.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// conversion-svc reaches the ledger by its registry name; here that name resolves to the fault-injecting proxy.
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "conversion.sweep-interval=1h")
@ActiveProfiles("acceptance")
@Import({ TestcontainersConfiguration.class, CucumberSpringConfig.Glue.class })
public class CucumberSpringConfig {

	@DynamicPropertySource
	static void externals(final DynamicPropertyRegistry registry) {
		registry.add("conversion.rates.url", () -> Externals.get().rates().baseUrl());
		// Only the host and the grpcPort metadata are read: conversion-svc no longer calls the ledger over HTTP.
		registry.add("spring.cloud.discovery.client.simple.instances.ledger-svc[0].uri", () -> "http://127.0.0.1:1");
		registry.add("spring.cloud.discovery.client.simple.instances.ledger-svc[0].metadata.grpcPort",
				() -> Externals.get().ledgerProxy().port());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Glue {

		@Bean
		Http http(final Environment environment) {
			return new Http(environment);
		}

		@Bean
		@ScenarioScope
		ScenarioState scenarioState() {
			return new ScenarioState();
		}

	}

}
