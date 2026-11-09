package io.ezeebit.ledger.mapper;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.BalanceResp;

public interface BalanceMapper {

	BalanceResp toResp(AccountEntity account, Currency currency);

}
