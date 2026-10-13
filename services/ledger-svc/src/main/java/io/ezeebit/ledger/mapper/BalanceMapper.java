package io.ezeebit.ledger.mapper;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.BalanceResp;
import org.springframework.stereotype.Component;

@Component
public class BalanceMapper {

	private final AmountFormat format;

	public BalanceMapper(final AmountFormat format) {
		this.format = format;
	}

	public BalanceResp toResp(final AccountEntity account, final Currency currency) {
		return new BalanceResp(account.currency(), format.format(account.balance(), currency.scale()));
	}

}
