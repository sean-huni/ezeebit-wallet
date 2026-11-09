package io.ezeebit.ledger.service.conversion;

import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;

public interface ConversionPostingService {

	ConversionPostingResp post(ConversionPostingReq request);

}
