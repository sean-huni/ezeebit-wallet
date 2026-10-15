package io.ezeebit.ledger.ctl;

import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.service.deposit.DepositService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Internal: called by the money-in rail, never routed by the gateway, so a merchant cannot credit itself.
@Tag(name = "Deposits")
@RestController
@RequestMapping("/internal/{version}/deposits")
public class DepositCtrl {

	private final DepositService deposits;

	public DepositCtrl(final DepositService deposits) {
		this.deposits = deposits;
	}

	@Operation(operationId = "createDeposit", summary = "Credit a merchant from the money-in rail",
			description = "Replaying the same Idempotency-Key with the same body answers 201 with the first answer, credited once.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<DepositResp> deposit(
			@Parameter(description = DocExamples.IDEMPOTENCY_KEY, example = "rail-transfer-1")
			@RequestHeader("Idempotency-Key") @NotBlank(message = "{validation.idempotencyKey.required}")
			@Size(max = 128, message = "{validation.idempotencyKey.size}")
			@Pattern(regexp = DepositReq.NO_CONTROL_CHARACTERS, message = "{validation.idempotencyKey.control}")
			final String idempotencyKey,
			@Valid @RequestBody final DepositReq request) {
		return new ApiResponse<>(deposits.deposit(idempotencyKey, request));
	}

}
