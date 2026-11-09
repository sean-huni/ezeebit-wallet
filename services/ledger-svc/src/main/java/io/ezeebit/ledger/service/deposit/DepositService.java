package io.ezeebit.ledger.service.deposit;

import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.DepositResp;

public interface DepositService {

	DepositResp deposit(String idempotencyKey, DepositReq request);

}
