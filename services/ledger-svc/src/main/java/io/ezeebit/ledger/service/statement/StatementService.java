package io.ezeebit.ledger.service.statement;

import java.util.UUID;

import io.ezeebit.ledger.dto.req.StatementReq;
import io.ezeebit.ledger.dto.resp.StatementResp;

public interface StatementService {

	StatementResp statement(UUID merchantId, StatementReq request);

}
