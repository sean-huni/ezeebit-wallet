package io.ezeebit.ledger.ctl;

import java.util.UUID;

import io.ezeebit.ledger.dto.req.StatementReq;
import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.StatementResp;
import io.ezeebit.ledger.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/entries")
public class StatementCtrl {

	private final StatementService statements;

	public StatementCtrl(final StatementService statements) {
		this.statements = statements;
	}

	@GetMapping
	public ApiResponse<StatementResp> entries(@PathVariable final UUID merchantId, @Valid final StatementReq request) {
		return new ApiResponse<>(statements.statement(merchantId, request));
	}

}
