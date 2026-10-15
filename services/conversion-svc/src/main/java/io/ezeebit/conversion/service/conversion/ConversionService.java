package io.ezeebit.conversion.service.conversion;

import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.dto.req.ConversionReq;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.exception.ConversionException;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversionService {

	private static final Logger LOG = LoggerFactory.getLogger(ConversionService.class);

	private final ConversionAcceptance acceptance;
	private final ConversionMetrics metrics;

	public ConversionService(final ConversionAcceptance acceptance, final ConversionMetrics metrics) {
		this.acceptance = acceptance;
		this.metrics = metrics;
	}

	public ConversionResp accept(final UUID merchantId, final String key, final ConversionReq request) {
		Observation span = metrics.spans().accept();
		return metrics.spans().observe(span, () -> acceptAndTag(span, merchantId, key, request));
	}

	private ConversionResp acceptAndTag(final Observation span, final UUID merchantId, final String key,
			final ConversionReq request) {
		try {
			ConversionResp answer = acceptance.acceptOnce(merchantId, key, request,
					(sell, buy) -> metrics.spans().pair(span, sell, buy));
			metrics.spans().accepted(span, answer.status());
			LOG.info("conversion accepted for {}/{}: {}", answer.sellCurrency(), answer.buyCurrency(), answer.status());
			return answer;
		}
		catch (ConversionException refusal) {
			metrics.spans().acceptRefused(span, refusal);
			LOG.info("conversion refused: {}", refusal.code());
			throw refusal;
		}
	}

}
