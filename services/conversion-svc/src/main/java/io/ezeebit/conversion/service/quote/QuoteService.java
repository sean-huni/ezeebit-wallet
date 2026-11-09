package io.ezeebit.conversion.service.quote;

import java.util.UUID;

import io.ezeebit.conversion.dto.req.QuoteReq;
import io.ezeebit.conversion.dto.resp.QuoteResp;

public interface QuoteService {

	QuoteResp quote(UUID merchantId, QuoteReq request);

}
