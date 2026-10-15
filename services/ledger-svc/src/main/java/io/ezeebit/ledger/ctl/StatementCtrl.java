package io.ezeebit.ledger.ctl;

import java.util.UUID;

import io.ezeebit.ledger.dto.req.StatementReq;
import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.StatementResp;
import io.ezeebit.ledger.service.statement.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Statement")
@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/entries")
public class StatementCtrl {

	private final StatementService statements;

	public StatementCtrl(final StatementService statements) {
		this.statements = statements;
	}

	@Operation(operationId = "listEntries", summary = "Statement: ledger entries with the balance after each",
			description = "Oldest first, in keyset pages: pass nextCursor back as `after` until it is null.")
	@GetMapping
	public ApiResponse<StatementResp> entries(
			@Parameter(description = DocExamples.MERCHANT_ID, example = DocExamples.MERCHANT) @PathVariable final UUID merchantId,
			@ParameterObject @Valid final StatementReq request) {
		return new ApiResponse<>(statements.statement(merchantId, request));
	}

}
