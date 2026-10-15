package io.ezeebit.ledger.service.conversion;

import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.service.CurrencyCatalog;
import io.ezeebit.ledger.service.idempotency.RequestFingerprint;
import org.springframework.stereotype.Component;

@Component
public class ConversionPostingIntake {

	private final CurrencyCatalog catalog;
	private final RequestFingerprint fingerprint;

	public ConversionPostingIntake(final CurrencyCatalog catalog, final RequestFingerprint fingerprint) {
		this.catalog = catalog;
		this.fingerprint = fingerprint;
	}

	public ConversionPostingCommand command(final ConversionPostingReq request) {
		Currency sellCurrency = catalog.require(request.sellCurrency());
		Currency buyCurrency = catalog.require(request.buyCurrency());
		if (sellCurrency.code().equals(buyCurrency.code())) {
			throw new LedgerException(ErrorCode.SAME_CURRENCY, "buyCurrency", request.buyCurrency());
		}
		Money sell = Money.parse(request.sellAmount(), sellCurrency);
		Money buy = Money.parse(request.buyAmount(), buyCurrency);
		String hash = fingerprint.of(request.merchantId().toString(), request.conversionId().toString(),
				request.sellCurrency(), request.sellAmount(), request.buyCurrency(), request.buyAmount(),
				request.deadline().toString());
		return new ConversionPostingCommand(new MerchantId(request.merchantId()), request.conversionId(), sell, buy,
				request.deadline(), hash);
	}

}
