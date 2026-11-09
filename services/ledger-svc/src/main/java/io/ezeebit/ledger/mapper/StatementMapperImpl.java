package io.ezeebit.ledger.mapper;

import java.util.UUID;

import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.dto.resp.StatementLineResp;
import org.springframework.stereotype.Component;

@Component
class StatementMapperImpl implements StatementMapper {

	private final AmountFormat format;

	StatementMapperImpl(final AmountFormat format) {
		this.format = format;
	}

	@Override
	public StatementLineResp toLine(final StatementLineEntity line, final Currency currency) {
		String amount = format.format(line.amount(), currency.scale());
		String balanceAfter = format.format(line.balanceAfter(), currency.scale());
		return new StatementLineResp(line.seq(), line.journalId(), line.type(), line.reference(), line.currency(), amount,
				balanceAfter, line.created());
	}

	@Override
	public DepositResp toDeposit(final StatementLineEntity line, final UUID merchantId, final Currency currency) {
		String amount = format.format(line.amount(), currency.scale());
		String balanceAfter = format.format(line.balanceAfter(), currency.scale());
		return new DepositResp(line.journalId(), merchantId, line.currency(), amount, balanceAfter, line.reference(),
				line.created());
	}

}
