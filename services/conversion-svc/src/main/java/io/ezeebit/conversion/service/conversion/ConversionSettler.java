package io.ezeebit.conversion.service.conversion;

import java.util.Optional;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.Settlement;

public interface ConversionSettler {

	Optional<Settlement> settle(ConversionEntity conversion, LedgerAnswer answer);

	ConversionEntity settleCommitted(ConversionEntity conversion, LedgerAnswer answer);

}
