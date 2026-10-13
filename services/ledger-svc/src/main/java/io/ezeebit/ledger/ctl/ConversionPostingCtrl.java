package io.ezeebit.ledger.ctl;

import java.util.UUID;

import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.req.VoidReq;
import io.ezeebit.ledger.dto.resp.ApiResponse;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.service.conversion.ConversionPostingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Conversion postings")
@RestController
@RequestMapping("/internal/{version}/conversion-postings")
public class ConversionPostingCtrl {

	private final ConversionPostingService postings;

	public ConversionPostingCtrl(final ConversionPostingService postings) {
		this.postings = postings;
	}

	@Operation(operationId = "postConversion", summary = "Post both legs of a conversion before its deadline",
			description = "Idempotent on conversionId. A funds or limit refusal is recorded as REFUSED and replayed for the same body.")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ConversionPostingResp> post(@Valid @RequestBody final ConversionPostingReq request) {
		return new ApiResponse<>(postings.post(request));
	}

	@Operation(operationId = "findConversionPosting", summary = "What the ledger recorded for a conversion",
			description = "Status POSTED, VOID, or REFUSED with its reason. Read by the conversion sweeper.")
	@GetMapping("/{conversionId}")
	public ApiResponse<ConversionPostingResp> find(@PathVariable final UUID conversionId,
			@Parameter(description = DocExamples.MERCHANT_ID, example = DocExamples.MERCHANT) @RequestParam final UUID merchantId) {
		return new ApiResponse<>(postings.find(conversionId, merchantId));
	}

	@Operation(operationId = "voidConversionPosting", summary = "Void a conversion so it can never post",
			description = "Idempotent. Answers the stored REFUSED journal when the posting was already refused.")
	@PostMapping("/{conversionId}/void")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ConversionPostingResp> voidPosting(@PathVariable final UUID conversionId,
			@Valid @RequestBody final VoidReq request) {
		return new ApiResponse<>(postings.voidPosting(conversionId, request.merchantId()));
	}

}
