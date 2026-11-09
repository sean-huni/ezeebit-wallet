package io.ezeebit.conversion.mapper;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.dto.resp.ConversionResp;

public interface ConversionMapper {

	ConversionResp toResp(ConversionEntity conversion, Quote quote);

}
