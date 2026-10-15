package io.ezeebit.conversion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

// Boot's default LoggingProducerListener logs a failed record's value (merchant, amounts) at ERROR; the
// publisher already logs the loss at WARN without the payload, so the template gets a listener that logs nothing.
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

	@Bean
	ProducerListener<Object, Object> quietProducerListener() {
		return new ProducerListener<>() {
		};
	}

}
