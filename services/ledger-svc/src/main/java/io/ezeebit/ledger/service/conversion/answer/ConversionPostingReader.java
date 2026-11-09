package io.ezeebit.ledger.service.conversion.answer;

import java.util.UUID;

import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;

public interface ConversionPostingReader {

	ConversionPostingResp find(UUID conversionId, UUID merchantId);

	ConversionPostingResp read(MerchantId merchant, UUID conversionId);

}
