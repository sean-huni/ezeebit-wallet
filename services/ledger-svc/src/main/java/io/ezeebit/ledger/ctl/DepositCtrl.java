package io.ezeebit.ledger.ctl;

import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.service.DepositService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Internal: called by the money-in rail, never routed by the gateway, so a merchant cannot credit itself.
@RestController
@RequestMapping("/internal/{version}/deposits")
public class DepositCtrl {

	private final DepositService deposits;

	public DepositCtrl(final DepositService deposits) {
		this.deposits = deposits;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<DepositResp> deposit(
			@RequestHeader("Idempotency-Key") @NotBlank(message = "{validation.idempotencyKey.required}")
			@Size(max = 128, message = "{validation.idempotencyKey.size}") final String idempotencyKey,
			@Valid @RequestBody final DepositReq request) {
		return new ApiResponse<>(deposits.deposit(idempotencyKey, request));
	}

}
