package io.ezeebit.conversion.service.port;

import io.ezeebit.conversion.domain.MarketRate;

// Fails closed: no rate is a ConversionException, never a cached or default rate.
public interface RateSource {

	MarketRate fetch(String base, String quote);

}
