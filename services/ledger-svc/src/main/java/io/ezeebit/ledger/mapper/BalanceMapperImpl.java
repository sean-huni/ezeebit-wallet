package io.ezeebit.ledger.mapper;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.BalanceResp;
import org.springframework.stereotype.Component;

@Component
class BalanceMapperImpl implements BalanceMapper {

	private final AmountFormat format;

	BalanceMapperImpl(final AmountFormat format) {
		this.format = format;
	}

	@Override
	public BalanceResp toResp(final AccountEntity account, final Currency currency) {
		String balance = format.format(account.balance(), currency.scale());
		return new BalanceResp(account.currency(), balance);
	}

}
