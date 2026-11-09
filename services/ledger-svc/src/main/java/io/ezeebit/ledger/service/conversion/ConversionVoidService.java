package io.ezeebit.ledger.service.conversion;

import java.util.UUID;

import io.ezeebit.ledger.dto.resp.ConversionPostingResp;

public interface ConversionVoidService {

	ConversionPostingResp voidPosting(UUID conversionId, UUID merchantId);

}
