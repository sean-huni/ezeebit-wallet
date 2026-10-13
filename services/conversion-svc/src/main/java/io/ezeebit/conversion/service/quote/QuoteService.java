package io.ezeebit.conversion.service.quote;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.repo.QuoteRepo;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.MarketRate;
import io.ezeebit.conversion.domain.Money;
import io.ezeebit.conversion.domain.PairPolicy;
import io.ezeebit.conversion.domain.Priced;
import io.ezeebit.conversion.domain.Pricing;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.domain.RateValidator;
import io.ezeebit.conversion.domain.Side;
import io.ezeebit.conversion.dto.req.QuoteReq;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.mapper.QuoteMapper;
import io.ezeebit.conversion.outbound.rates.RateClient;
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
	private final RateClient rates;
	private final RateValidator validator;
	private final QuoteRepo quotes;
	private final QuoteMapper mapper;
	private final ConversionProperties properties;
	private final Clock clock;
	private final ConversionMetrics metrics;

	public QuoteService(final CurrencyCatalog catalog, final RateClient rates, final RateValidator validator,
			final QuoteRepo quotes, final QuoteMapper mapper, final ConversionProperties properties, final Clock clock,
			final ConversionMetrics metrics) {
		this.catalog = catalog;
		this.rates = rates;
		this.validator = validator;
		this.quotes = quotes;
		this.mapper = mapper;
		this.properties = properties;
		this.clock = clock;
		this.metrics = metrics;
	}

	public QuoteResp quote(final UUID merchantId, final QuoteReq request) {
		String pair = pairTag(request);
		Observation span = metrics.spans().quote(pair);
		return metrics.spans().observe(span, () -> issue(span, pair, merchantId, request));
	}

	private QuoteResp issue(final Observation span, final String pair, final UUID merchantId, final QuoteReq request) {
		try {
			QuoteResp quote = price(merchantId, request);
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

	private QuoteResp price(final UUID merchantId, final QuoteReq request) {
		Currency sell = catalog.require(request.sell(), "sell");
		Currency buy = catalog.require(request.buy(), "buy");
		PairPolicy policy = catalog.policy(sell, buy);
		String sellAmount = request.sellAmount();
		boolean bySell = sellAmount != null;
		// @ExactlyOneAmount guarantees buyAmount is present whenever sellAmount is not.
		Money fixed = bySell ? Money.parse(sellAmount, sell)
				: Money.parse(Objects.requireNonNull(request.buyAmount(), "buyAmount"), buy);
		MarketRate rate = rates.fetch(sell.code(), buy.code());
		Instant now = clock.instant();
		validator.check(rate, policy,
				quotes.recentRate(sell.code(), buy.code(), now.minus(properties.rates().jumpWindow())), now);
		Priced priced = Pricing.price(fixed, bySell ? Side.SELL : Side.BUY, bySell ? buy : sell, rate.rate(),
				policy.spreadBps());
		Quote quote = mapper.toDomain(merchantId, priced, rate, policy.spreadBps(), now.plus(properties.quoteTtl()));
		quotes.insert(mapper.toEntity(quote));
		return mapper.toResp(quote);
	}

}
