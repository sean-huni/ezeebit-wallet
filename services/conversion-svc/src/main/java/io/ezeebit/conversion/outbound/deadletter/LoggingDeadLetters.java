package io.ezeebit.conversion.outbound.deadletter;

import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.service.port.DeadLetter;
import io.ezeebit.conversion.service.port.DeadLetters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Placeholder until the Kafka publisher (Task 10) replaces it.
@Component
public class LoggingDeadLetters implements DeadLetters {

	private static final Logger LOG = LoggerFactory.getLogger(LoggingDeadLetters.class);

	@Override
	public void record(final DeadLetter letter) {
		LOG.warn("ledger dead letter: op={} reason={} conversion={} attempts={} lastStatus={} traceId={}",
				LogText.oneLine(letter.op()), LogText.oneLine(letter.reason()), letter.conversionId(), letter.attempts(),
				LogText.oneLine(letter.lastStatus()), LogText.oneLine(letter.traceId()));
	}

}
