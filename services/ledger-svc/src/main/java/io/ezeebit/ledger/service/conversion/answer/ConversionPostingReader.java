package io.ezeebit.ledger.service.conversion.answer;

import java.util.UUID;

import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.springframework.stereotype.Service;

// First answer and every replay are built from the stored rows, so a replay is byte-identical.
@Service
public class ConversionPostingReader {

	private final JournalRepo journals;
	private final ConversionPostingAssembler assembler;

	public ConversionPostingReader(final JournalRepo journals, final ConversionPostingAssembler assembler) {
		this.journals = journals;
		this.assembler = assembler;
	}

	public ConversionPostingResp find(final UUID conversionId, final UUID merchantId) {
		return read(new MerchantId(merchantId), conversionId);
	}

	public ConversionPostingResp read(final MerchantId merchant, final UUID conversionId) {
		return assembler.assemble(journals.findByIdentity(merchant.value(), JournalType.CONVERSION.name(),
				conversionId.toString()).orElseThrow(() -> new LedgerException(ErrorCode.POSTING_NOT_FOUND, "conversionId",
						conversionId.toString())));
	}

}
