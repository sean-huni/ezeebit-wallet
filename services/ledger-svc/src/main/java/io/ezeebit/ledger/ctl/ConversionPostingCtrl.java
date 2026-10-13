package io.ezeebit.ledger.ctl;

import java.util.UUID;

import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.req.VoidReq;
import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.service.ConversionPostingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Internal: called by conversion-svc and its sweeper, never routed by the gateway.
@RestController
@RequestMapping("/internal/{version}/conversion-postings")
public class ConversionPostingCtrl {

	private final ConversionPostingService postings;

	public ConversionPostingCtrl(final ConversionPostingService postings) {
		this.postings = postings;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ConversionPostingResp> post(@Valid @RequestBody final ConversionPostingReq request) {
		return new ApiResponse<>(postings.post(request));
	}

	@GetMapping("/{conversionId}")
	public ApiResponse<ConversionPostingResp> find(@PathVariable final UUID conversionId,
			@RequestParam final UUID merchantId) {
		return new ApiResponse<>(postings.find(conversionId, merchantId));
	}

	@PostMapping("/{conversionId}/void")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ConversionPostingResp> voidPosting(@PathVariable final UUID conversionId,
			@Valid @RequestBody final VoidReq request) {
		return new ApiResponse<>(postings.voidPosting(conversionId, request.merchantId()));
	}

}
