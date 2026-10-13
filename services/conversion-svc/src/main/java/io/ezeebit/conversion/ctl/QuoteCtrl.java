package io.ezeebit.conversion.ctl;

import java.util.UUID;

import io.ezeebit.conversion.dto.req.QuoteReq;
import io.ezeebit.conversion.dto.resp.ApiResponse;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import io.ezeebit.conversion.service.quote.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// No Idempotency-Key: a quote moves no money and is cheap to repeat.
@Tag(name = "Quotes")
@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/quotes")
public class QuoteCtrl {

	private final QuoteService quotes;

	public QuoteCtrl(final QuoteService quotes) {
		this.quotes = quotes;
	}

	@Operation(operationId = "createQuote", summary = "Price a conversion, firm for 15 seconds",
			description = "Exactly one of sellAmount (what leaves) or buyAmount (what arrives). Moves no money: no Idempotency-Key.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<QuoteResp> quote(
			@Parameter(description = DocExamples.MERCHANT_ID, example = DocExamples.MERCHANT) @PathVariable final UUID merchantId,
			@Valid @RequestBody final QuoteReq request) {
		return new ApiResponse<>(quotes.quote(merchantId, request));
	}

}
