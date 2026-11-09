package io.ezeebit.ledger.service.conversion.answer;

import java.util.UUID;
import java.util.function.Supplier;

import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;

public interface ConversionPostingResponder {

	ConversionPostingResp respond(String operation, Supplier<Outcome> action, MerchantId merchant, UUID conversionId);

}
