package io.ezeebit.ledger.service.deposit;

import java.util.UUID;

import io.ezeebit.ledger.common.metrics.LedgerMetrics;
import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.service.CurrencyCatalog;
import io.ezeebit.ledger.service.idempotency.RequestFingerprint;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Not transactional itself: the duplicate key must be caught outside the transaction it rolled back.
@Service
public class DepositService {

	private static final Logger LOG = LoggerFactory.getLogger(DepositService.class);

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
		Observation span = metrics.depositSpan();
		return metrics.observe(span, () -> post(span, idempotencyKey, request));
	}

	private DepositResp post(final Observation span, final String idempotencyKey, final DepositReq request) {
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
			metrics.deposit(span, currency, outcome, null);
			LOG.info("deposit {} in {}", outcome, currency.code().value());
			return answer;
		}
		catch (LedgerException refusal) {
			metrics.deposit(span, currency, Outcome.REFUSED, refusal.code());
			LOG.info("deposit refused: {}", refusal.code());
			throw refusal;
		}
	}

}
