package io.ezeebit.ledger.service.conversion;

import java.util.UUID;
import java.util.function.Supplier;

import io.ezeebit.ledger.common.metrics.LedgerMetrics;
import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.mapper.ConversionPostingMapper;
import io.ezeebit.ledger.service.CurrencyCatalog;
import io.ezeebit.ledger.service.idempotency.RequestFingerprint;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Not transactional itself: a duplicate identity must be caught outside the transaction it rolled back.
@Service
public class ConversionPostingService {

	private static final Logger LOG = LoggerFactory.getLogger(ConversionPostingService.class);

	private static final String VOID = "VOID";

	private final CurrencyCatalog catalog;
	private final RequestFingerprint fingerprint;
	private final ConversionPoster poster;
	private final ConversionVoider voider;
	private final ConversionReplayer replayer;
	private final RefusalRecorder refusals;
	private final JournalRepo journals;
	private final EntryRepo entries;
	private final ConversionPostingMapper mapper;
	private final LedgerMetrics metrics;

	public ConversionPostingService(final CurrencyCatalog catalog, final RequestFingerprint fingerprint,
			final ConversionPoster poster, final ConversionVoider voider, final ConversionReplayer replayer,
			final RefusalRecorder refusals, final JournalRepo journals, final EntryRepo entries,
			final ConversionPostingMapper mapper, final LedgerMetrics metrics) {
		this.catalog = catalog;
		this.fingerprint = fingerprint;
		this.poster = poster;
		this.voider = voider;
		this.replayer = replayer;
		this.refusals = refusals;
		this.journals = journals;
		this.entries = entries;
		this.mapper = mapper;
		this.metrics = metrics;
	}

	public ConversionPostingResp post(final ConversionPostingReq request) {
		return counted("post", () -> postOrReplay(request), new MerchantId(request.merchantId()), request.conversionId());
	}

	private Outcome postOrReplay(final ConversionPostingReq request) {
		Currency sellCurrency = catalog.require(request.sellCurrency());
		Currency buyCurrency = catalog.require(request.buyCurrency());
		if (sellCurrency.code().equals(buyCurrency.code())) {
			throw new LedgerException(ErrorCode.SAME_CURRENCY, "buyCurrency", request.buyCurrency());
		}
		Money sell = Money.parse(request.sellAmount(), sellCurrency);
		Money buy = Money.parse(request.buyAmount(), buyCurrency);
		MerchantId merchant = new MerchantId(request.merchantId());
		String hash = fingerprint.of(request.merchantId().toString(), request.conversionId().toString(),
				request.sellCurrency(), request.sellAmount(), request.buyCurrency(), request.buyAmount(),
				request.deadline().toString());
		try {
			poster.post(new ConversionPostingCommand(merchant, request.conversionId(), sell, buy, request.deadline(), hash));
			return Outcome.POSTED;
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedPosting(merchant, request.conversionId(), hash, request.sellCurrency(), request.buyCurrency());
		}
		catch (LedgerException refusal) {
			refusals.recordAndRethrow(merchant, request, hash, refusal);
		}
		return Outcome.REPLAYED;
	}

	public ConversionPostingResp find(final UUID conversionId, final UUID merchantId) {
		return read(new MerchantId(merchantId), conversionId);
	}

	public ConversionPostingResp voidPosting(final UUID conversionId, final UUID merchantId) {
		MerchantId merchant = new MerchantId(merchantId);
		return counted("void", () -> voidOrReplay(merchant, conversionId), merchant, conversionId);
	}

	private Outcome voidOrReplay(final MerchantId merchant, final UUID conversionId) {
		try {
			voider.voidPosting(merchant, conversionId, fingerprint.of(VOID, merchant.value().toString(),
					conversionId.toString()));
			return Outcome.VOIDED;
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedVoid(merchant, conversionId);
			return Outcome.REPLAYED;
		}
	}

	// Every answer is counted once, on its span too: its outcome, or the code of the refusal it gave (a replayed
	// refusal included).
	private ConversionPostingResp counted(final String operation, final Supplier<Outcome> action, final MerchantId merchant,
			final UUID conversionId) {
		Observation span = metrics.postingSpan(operation);
		return metrics.observe(span, () -> answer(span, operation, action, merchant, conversionId));
	}

	private ConversionPostingResp answer(final Observation span, final String operation, final Supplier<Outcome> action,
			final MerchantId merchant, final UUID conversionId) {
		try {
			Outcome outcome = action.get();
			ConversionPostingResp answer = read(merchant, conversionId);
			metrics.conversionPosting(span, outcome, null);
			LOG.info("conversion {} {}", operation, outcome);
			return answer;
		}
		catch (LedgerException refusal) {
			metrics.conversionPosting(span, Outcome.REFUSED, refusal.code());
			LOG.info("conversion {} refused: {}", operation, refusal.code());
			throw refusal;
		}
	}

	// First answer and every replay are built from the stored rows, so a replay is byte-identical.
	private ConversionPostingResp read(final MerchantId merchant, final UUID conversionId) {
		JournalEntity journal = journals.findByIdentity(merchant.value(), JournalType.CONVERSION.name(),
				conversionId.toString()).orElseThrow(() -> new LedgerException(ErrorCode.POSTING_NOT_FOUND, "conversionId",
						conversionId.toString()));
		return mapper.toResp(journal, entries.merchantLines(journal.id()), catalog.all());
	}

}
