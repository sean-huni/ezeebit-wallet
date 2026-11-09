package io.ezeebit.conversion.service.conversion;

import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.dto.resp.ConversionResp;

public interface ConversionReader {

	ConversionResp find(UUID merchantId, UUID conversionId);

	Optional<ConversionEntity> byKey(UUID merchantId, String key);

	ConversionResp answer(ConversionEntity conversion);

}
