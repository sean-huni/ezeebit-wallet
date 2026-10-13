package io.ezeebit.conversion.ctl;

import java.util.UUID;

import io.ezeebit.conversion.dto.req.QuoteReq;
import io.ezeebit.conversion.dto.resp.ApiResponse;
import io.ezeebit.conversion.dto.resp.QuoteResp;
import io.ezeebit.conversion.service.QuoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// No Idempotency-Key: a quote moves no money and is cheap to repeat.
@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/quotes")
public class QuoteCtrl {

	private final QuoteService quotes;

	public QuoteCtrl(final QuoteService quotes) {
		this.quotes = quotes;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<QuoteResp> quote(@PathVariable final UUID merchantId, @Valid @RequestBody final QuoteReq request) {
		return new ApiResponse<>(quotes.quote(merchantId, request));
	}

}
