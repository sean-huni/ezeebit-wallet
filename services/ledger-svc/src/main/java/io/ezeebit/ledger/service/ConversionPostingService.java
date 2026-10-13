package io.ezeebit.ledger.service;

import java.util.UUID;

import io.ezeebit.ledger.common.RequestFingerprint;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

// Not transactional itself: a duplicate identity must be caught outside the transaction it rolled back.
@Service
public class ConversionPostingService {

	private static final String VOID = "VOID";

	private final CurrencyCatalog catalog;
	private final RequestFingerprint fingerprint;
	private final ConversionPoster poster;
	private final ConversionVoider voider;
	private final ConversionReplayer replayer;
	private final JournalRepo journals;
	private final EntryRepo entries;
	private final ConversionPostingMapper mapper;

	public ConversionPostingService(final CurrencyCatalog catalog, final RequestFingerprint fingerprint,
			final ConversionPoster poster, final ConversionVoider voider, final ConversionReplayer replayer,
			final JournalRepo journals, final EntryRepo entries, final ConversionPostingMapper mapper) {
		this.catalog = catalog;
		this.fingerprint = fingerprint;
		this.poster = poster;
		this.voider = voider;
		this.replayer = replayer;
		this.journals = journals;
		this.entries = entries;
		this.mapper = mapper;
	}

	public ConversionPostingResp post(final ConversionPostingReq request) {
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
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedPosting(merchant, request.conversionId(), hash);
		}
		return read(merchant, request.conversionId());
	}

	public ConversionPostingResp find(final UUID conversionId, final UUID merchantId) {
		return read(new MerchantId(merchantId), conversionId);
	}

	public ConversionPostingResp voidPosting(final UUID conversionId, final UUID merchantId) {
		MerchantId merchant = new MerchantId(merchantId);
		String hash = fingerprint.of(VOID, merchantId.toString(), conversionId.toString());
		try {
			voider.voidPosting(merchant, conversionId, hash);
		}
		catch (DuplicateKeyException duplicate) {
			replayer.storedVoid(merchant, conversionId);
		}
		return read(merchant, conversionId);
	}

	// First answer and every replay are built from the stored rows, so a replay is byte-identical.
	private ConversionPostingResp read(final MerchantId merchant, final UUID conversionId) {
		JournalEntity journal = journals.findByIdentity(merchant.value(), JournalType.CONVERSION.name(),
				conversionId.toString()).orElseThrow(() -> new LedgerException(ErrorCode.NOT_FOUND, null));
		return mapper.toResp(journal, entries.merchantLines(journal.id()), catalog.all());
	}

}
