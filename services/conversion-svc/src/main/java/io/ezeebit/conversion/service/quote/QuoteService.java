package io.ezeebit.conversion.service.quote;

import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.dto.req.QuoteReq;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.service.CurrencyCatalog;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// A quote moves no money: validate the pair, fetch and check the rate, price it, store it with a short life.
@Service
public class QuoteService {

	private static final Logger LOG = LoggerFactory.getLogger(QuoteService.class);

	private final CurrencyCatalog catalog;
	private final QuotePricer pricer;
	private final ConversionMetrics metrics;

	public QuoteService(final CurrencyCatalog catalog, final QuotePricer pricer, final ConversionMetrics metrics) {
		this.catalog = catalog;
		this.pricer = pricer;
		this.metrics = metrics;
	}

	public QuoteResp quote(final UUID merchantId, final QuoteReq request) {
		String pair = pairTag(request);
		Observation span = metrics.spans().quote(pair);
		return metrics.spans().observe(span, () -> issue(span, pair, merchantId, request));
	}

	private QuoteResp issue(final Observation span, final String pair, final UUID merchantId, final QuoteReq request) {
		try {
			QuoteResp quote = pricer.price(merchantId, request);
			metrics.quoteIssued(pair);
			metrics.spans().quoteIssued(span);
			LOG.info("quote issued for {}", pair);
			return quote;
		}
		catch (ConversionException refusal) {
			metrics.quoteRefused(pair, refusal);
			metrics.spans().quoteRefused(span, refusal);
			LOG.info("quote refused for {}: {}", pair, refusal.code());
			throw refusal;
		}
	}

	// Two known currency codes or "unknown", so the tag has at most 26 values whatever a caller sends.
	private String pairTag(final QuoteReq request) {
		return catalog.isKnown(request.sell()) && catalog.isKnown(request.buy())
				? "%s/%s".formatted(request.sell(), request.buy()) : "unknown";
	}

}
