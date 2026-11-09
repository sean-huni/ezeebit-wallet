package io.ezeebit.ledger.mapper;

import io.ezeebit.ledger.data.model.CurrencyEntity;
import io.ezeebit.ledger.domain.Currency;

public interface CurrencyMapper {

	Currency toDomain(CurrencyEntity entity);

}
