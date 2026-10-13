package io.ezeebit.conversion.ctl;

import java.util.UUID;

import io.ezeebit.conversion.dto.req.ConversionReq;
import io.ezeebit.conversion.dto.resp.ApiResponse;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.service.ConversionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/merchants/{merchantId}/conversions")
public class ConversionCtrl {

	private static final String COMPLETED = "COMPLETED";

	private final ConversionService conversions;

	public ConversionCtrl(final ConversionService conversions) {
		this.conversions = conversions;
	}

	// 201 when the money moved, 202 while the ledger's answer is still unknown.
	@PostMapping
	public ResponseEntity<ApiResponse<ConversionResp>> convert(@PathVariable final UUID merchantId,
			@RequestHeader("Idempotency-Key") @NotBlank(message = "{validation.idempotencyKey.required}")
			@Size(max = 128, message = "{validation.idempotencyKey.size}")
			@Pattern(regexp = ConversionReq.NO_CONTROL_CHARACTERS, message = "{validation.idempotencyKey.control}")
			final String idempotencyKey,
			@Valid @RequestBody final ConversionReq request) {
		ConversionResp conversion = conversions.accept(merchantId, idempotencyKey, request);
		HttpStatus status = COMPLETED.equals(conversion.status()) ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
		return ResponseEntity.status(status).body(new ApiResponse<>(conversion));
	}

	@GetMapping("/{conversionId}")
	public ApiResponse<ConversionResp> find(@PathVariable final UUID merchantId, @PathVariable final UUID conversionId) {
		return new ApiResponse<>(conversions.find(merchantId, conversionId));
	}

}
