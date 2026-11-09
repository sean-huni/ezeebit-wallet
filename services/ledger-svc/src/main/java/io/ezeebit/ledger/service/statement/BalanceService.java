package io.ezeebit.ledger.service.statement;

import java.util.List;
import java.util.UUID;

import io.ezeebit.ledger.dto.resp.BalanceResp;

public interface BalanceService {

	List<BalanceResp> balances(UUID merchantId);

}
