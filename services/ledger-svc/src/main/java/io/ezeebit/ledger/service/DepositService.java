package io.ezeebit.ledger.service;

import java.util.UUID;

import io.ezeebit.ledger.common.LedgerMetrics;
import io.ezeebit.ledger.common.LedgerMetrics.Outcome;
import io.ezeebit.ledger.common.RequestFingerprint;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Not transactional itself: the duplicate key must be caught outside the transaction it rolled back.
@Service
public class DepositService {

	private final CurrencyCatalog catalog;
	private final RequestFingerprint fingerprint;
	private final DepositPoster poster;
	private final DepositReplayer replayer;
	private final DepositReader reader;
	private final LedgerMetrics metrics;

	public DepositService(final CurrencyCatalog catalog, final RequestFingerprint fingerprint, final DepositPoster poster,
			final DepositReplayer replayer, final DepositReader reader, final LedgerMetrics metrics) {
		this.catalog = catalog;
		this.fingerprint = fingerprint;
		this.poster = poster;
		this.replayer = replayer;
		this.reader = reader;
		this.metrics = metrics;
	}

	// First call and every replay answer from the stored rows, so a replay is byte-identical.
	public DepositResp deposit(final String idempotencyKey, final DepositReq request) {
		Currency currency = null;
		try {
			currency = catalog.require(request.currency());
			Money amount = Money.parse(request.amount(), currency);
			MerchantId merchant = new MerchantId(request.merchantId());
			String hash = fingerprint.of(request.merchantId().toString(), request.currency(), request.amount(),
					request.reference());
			UUID journalId;
			Outcome outcome = Outcome.POSTED;
			try {
				journalId = poster.post(new DepositCommand(merchant, amount, request.reference(), idempotencyKey, hash));
			}
			catch (DuplicateKeyException duplicate) {
				journalId = replayer.storedJournal(merchant, idempotencyKey, hash);
				outcome = Outcome.REPLAYED;
			}
			DepositResp answer = reader.read(journalId, merchant, currency);
			metrics.deposit(currency, outcome, null);
			return answer;
		}
		catch (LedgerException refusal) {
			metrics.deposit(currency, Outcome.REFUSED, refusal.code());
			throw refusal;
		}
	}

}
