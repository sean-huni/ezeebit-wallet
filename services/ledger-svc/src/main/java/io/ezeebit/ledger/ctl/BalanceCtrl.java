package io.ezeebit.ledger.ctl;

import java.util.List;
import java.util.UUID;

import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.BalanceResp;
import io.ezeebit.ledger.service.statement.BalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Balances")
@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/balances")
public class BalanceCtrl {

	private final BalanceService balances;

	public BalanceCtrl(final BalanceService balances) {
		this.balances = balances;
	}

	@Operation(operationId = "getBalances", summary = "Balance per currency",
			description = "One row per currency the merchant has ever held, zero balances included. Amounts are decimal strings.")
	@GetMapping
	public ApiResponse<List<BalanceResp>> balances(
			@Parameter(description = DocExamples.MERCHANT_ID, example = DocExamples.MERCHANT) @PathVariable final UUID merchantId) {
		return new ApiResponse<>(balances.balances(merchantId));
	}

}
