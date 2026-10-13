package io.ezeebit.ledger.mapper;

import java.util.UUID;

import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.dto.resp.StatementLineResp;
import org.springframework.stereotype.Component;

@Component
public class StatementMapper {

	private final AmountFormat format;

	public StatementMapper(final AmountFormat format) {
		this.format = format;
	}

	public StatementLineResp toLine(final StatementLineEntity line, final Currency currency) {
		return new StatementLineResp(line.seq(), line.journalId(), line.type(), line.reference(), line.currency(),
				format.format(line.amount(), currency.scale()), format.format(line.balanceAfter(), currency.scale()),
				line.created());
	}

	public DepositResp toDeposit(final StatementLineEntity line, final UUID merchantId, final Currency currency) {
		return new DepositResp(line.journalId(), merchantId, line.currency(), format.format(line.amount(), currency.scale()),
				format.format(line.balanceAfter(), currency.scale()), line.reference(), line.created());
	}

}
