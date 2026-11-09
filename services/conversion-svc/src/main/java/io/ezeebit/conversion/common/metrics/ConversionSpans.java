package io.ezeebit.conversion.common.metrics;

import java.util.Map;
import java.util.function.Supplier;

import io.ezeebit.conversion.exception.ConversionException;
import io.micrometer.observation.Observation;

public interface ConversionSpans {

	String QUOTE = "ezeebit.quote";
	String ACCEPT = "ezeebit.conversion.accept";
	String SWEEP = "ezeebit.sweep";

	Observation quote(String pair);

	Observation accept();

	Observation sweep();

	<T> T observe(Observation span, Supplier<T> step);

	void quoteIssued(Observation span);

	void quoteRefused(Observation span, ConversionException refusal);

	void pair(Observation span, String sell, String buy);

	void accepted(Observation span, String status);

	void acceptRefused(Observation span, ConversionException refusal);

	void swept(Observation span, Map<String, Integer> actions);

}
