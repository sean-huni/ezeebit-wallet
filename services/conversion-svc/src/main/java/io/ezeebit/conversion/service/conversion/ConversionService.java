package io.ezeebit.conversion.service.conversion;

import java.util.UUID;

import io.ezeebit.conversion.dto.req.ConversionReq;
import io.ezeebit.conversion.dto.resp.ConversionResp;

public interface ConversionService {

	ConversionResp accept(UUID merchantId, String key, ConversionReq request);

}
