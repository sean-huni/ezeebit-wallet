package io.ezeebit.conversion.outbound.deadletter;

import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.service.port.DeadLetter;
import io.ezeebit.conversion.service.port.DeadLetters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class KafkaDeadLetterPublisher implements DeadLetters {

	private static final Logger LOG = LoggerFactory.getLogger(KafkaDeadLetterPublisher.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final KafkaTemplate<String, String> template;
	private final ConversionMetrics metrics;
	private final String topic;

	public KafkaDeadLetterPublisher(final KafkaTemplate<String, String> template, final ConversionMetrics metrics,
			final ConversionProperties properties) {
		this.template = template;
		this.metrics = metrics;
		this.topic = properties.deadLetterTopic();
	}

	// Counted before sending: the alert is about the ledger giving up, whether or not the broker hears of it.
	@Override
	public void record(final DeadLetter letter) {
		metrics.deadLettered(letter.op(), letter.reason());
		LOG.warn("ledger dead letter: op={} reason={} conversion={} attempts={} lastStatus={} lastReason={} traceId={}",
				LogText.oneLine(letter.op()), LogText.oneLine(letter.reason()), letter.conversionId(), letter.attempts(),
				LogText.oneLine(letter.lastStatus()), LogText.oneLine(letter.lastReason()),
				LogText.oneLine(letter.traceId()));
		try {
			template.send(topic, String.valueOf(letter.conversionId()), JSON.writeValueAsString(letter))
					.whenComplete((result, ex) -> {
						if (ex != null) {
							lost(letter, ex);
						}
					});
		}
		// A max.block.ms timeout throws here, as do a closed producer and Jackson; the caller must never see one.
		catch (RuntimeException ex) {
			lost(letter, ex);
		}
	}

	private void lost(final DeadLetter letter, final Throwable ex) {
		metrics.deadLetterLost(letter.op());
		LOG.warn("dead letter not published: op={} conversion={} cause={}", LogText.oneLine(letter.op()),
				letter.conversionId(), LogText.oneLine(ex.getMessage()));
	}

}
