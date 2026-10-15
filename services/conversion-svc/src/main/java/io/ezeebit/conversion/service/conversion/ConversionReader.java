package io.ezeebit.conversion.service.conversion;

import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.domain.RejectionReason;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import io.ezeebit.conversion.mapper.ConversionMapper;
import io.ezeebit.conversion.service.quote.QuoteReader;
import org.springframework.stereotype.Service;

@Service
public class ConversionReader {

	private final ConversionRepo conversions;
	private final QuoteReader quotes;
	private final ConversionMapper mapper;

	public ConversionReader(final ConversionRepo conversions, final QuoteReader quotes, final ConversionMapper mapper) {
		this.conversions = conversions;
		this.quotes = quotes;
		this.mapper = mapper;
	}

	public ConversionResp find(final UUID merchantId, final UUID conversionId) {
		ConversionEntity conversion = conversions.find(conversionId, merchantId).orElseThrow(
				() -> new ConversionException(ErrorCode.CONVERSION_NOT_FOUND, "conversionId", conversionId));
		return mapper.toResp(conversion, quotes.of(conversion.quote()));
	}

	public Optional<ConversionEntity> byKey(final UUID merchantId, final String key) {
		return conversions.findByKey(merchantId, key);
	}

	public ConversionResp answer(final ConversionEntity conversion) {
		if (ConversionStatus.REJECTED.name().equals(conversion.status())) {
			throw new ConversionException(RejectionReason.valueOf(conversion.reason()).answer(), null, conversion.id(), conversion.reason())
					.with("conversionId", conversion.id()).with("reason", conversion.reason());
		}
		return mapper.toResp(conversion, quotes.of(conversion.quote()));
	}

}
