package io.ezeebit.ledger.service.deposit;

import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.service.CurrencyCatalog;
import io.ezeebit.ledger.service.idempotency.RequestFingerprint;
import org.springframework.stereotype.Component;

// Two steps, so a refusal of the amount is still counted under the currency that was already admitted.
@Component
public class DepositIntake {

	private final CurrencyCatalog catalog;
	private final RequestFingerprint fingerprint;

	public DepositIntake(final CurrencyCatalog catalog, final RequestFingerprint fingerprint) {
		this.catalog = catalog;
		this.fingerprint = fingerprint;
	}

	public Currency currency(final DepositReq request) {
		return catalog.require(request.currency());
	}

	public DepositCommand command(final String idempotencyKey, final DepositReq request, final Currency currency) {
		Money amount = Money.parse(request.amount(), currency);
		MerchantId merchant = new MerchantId(request.merchantId());
		String hash = fingerprint.of(request.merchantId().toString(), request.currency(), request.amount(),
				request.reference());
		return new DepositCommand(merchant, amount, request.reference(), idempotencyKey, hash);
	}

}
