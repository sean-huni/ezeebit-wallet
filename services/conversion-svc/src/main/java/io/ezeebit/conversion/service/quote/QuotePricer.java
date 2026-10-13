package io.ezeebit.conversion.service.quote;

import java.util.Objects;
import java.util.UUID;

import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.Money;
import io.ezeebit.conversion.domain.PairPolicy;
import io.ezeebit.conversion.domain.Priced;
import io.ezeebit.conversion.domain.Pricing;
import io.ezeebit.conversion.domain.Side;
import io.ezeebit.conversion.dto.req.QuoteReq;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import io.ezeebit.conversion.service.CurrencyCatalog;
import io.ezeebit.conversion.service.quote.RateCheck.CheckedRate;
import org.springframework.stereotype.Component;

@Component
public class QuotePricer {

	private final CurrencyCatalog catalog;
	private final RateCheck rates;
	private final QuoteWriter writer;

	public QuotePricer(final CurrencyCatalog catalog, final RateCheck rates, final QuoteWriter writer) {
		this.catalog = catalog;
		this.rates = rates;
		this.writer = writer;
	}

	public QuoteResp price(final UUID merchantId, final QuoteReq request) {
		Currency sell = catalog.require(request.sell(), "sell");
		Currency buy = catalog.require(request.buy(), "buy");
		PairPolicy policy = catalog.policy(sell, buy);
		String sellAmount = request.sellAmount();
		boolean bySell = sellAmount != null;
		// @ExactlyOneAmount guarantees buyAmount is present whenever sellAmount is not.
		Money fixed = bySell ? Money.parse(sellAmount, sell)
				: Money.parse(Objects.requireNonNull(request.buyAmount(), "buyAmount"), buy);
		CheckedRate checked = rates.fetch(sell, buy, policy);
		Priced priced = Pricing.price(fixed, bySell ? Side.SELL : Side.BUY, bySell ? buy : sell, checked.rate().rate(),
				policy.spreadBps());
		return writer.store(merchantId, priced, checked.rate(), policy.spreadBps(), checked.checkedAt());
	}

}
