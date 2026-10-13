package io.ezeebit.ledger.ctl;

import java.util.List;
import java.util.UUID;

import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.BalanceResp;
import io.ezeebit.ledger.service.statement.BalanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/balances")
public class BalanceCtrl {

	private final BalanceService balances;

	public BalanceCtrl(final BalanceService balances) {
		this.balances = balances;
	}

	@GetMapping
	public ApiResponse<List<BalanceResp>> balances(@PathVariable final UUID merchantId) {
		return new ApiResponse<>(balances.balances(merchantId));
	}

}
