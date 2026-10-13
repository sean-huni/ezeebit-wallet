package io.ezeebit.ledger.mapper;

import io.ezeebit.ledger.data.model.CurrencyEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.CurrencyCode;
import io.ezeebit.ledger.domain.CurrencyKind;
import org.springframework.stereotype.Component;

@Component
public class CurrencyMapper {

	public Currency toDomain(final CurrencyEntity entity) {
		return new Currency(new CurrencyCode(entity.code()), CurrencyKind.valueOf(entity.kind()), entity.scale());
	}

}
