package io.ezeebit.ledger.mapper;

import java.util.UUID;

import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.dto.resp.StatementLineResp;

public interface StatementMapper {

	StatementLineResp toLine(StatementLineEntity line, Currency currency);

	DepositResp toDeposit(StatementLineEntity line, UUID merchantId, Currency currency);

}
