package io.ezeebit.conversion.ctl;

import java.util.UUID;

import io.ezeebit.conversion.dto.req.ConversionReq;
import io.ezeebit.conversion.dto.resp.ApiResponse;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import io.ezeebit.conversion.service.conversion.ConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Conversions")
@RestController
@RequestMapping("/{version:v\\d+}/merchants/{merchantId}/conversions")
public class ConversionCtrl {

	private static final String COMPLETED = "COMPLETED";

	private final ConversionService conversions;

	public ConversionCtrl(final ConversionService conversions) {
		this.conversions = conversions;
	}

	// 201 when the money moved, 202 while the ledger's answer is still unknown. The swagger ApiResponse is qualified because
	// the envelope record shares its simple name.
	@Operation(operationId = "createConversion", summary = "Accept a quote: move the money",
			description = "The quote is single-use. The same Idempotency-Key with the same body answers the first answer again.",
			responses = {
				@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "COMPLETED: the money moved",
						useReturnTypeSchema = true),
				@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
						description = "PENDING: the ledger's answer is not known yet; read the conversion back",
						useReturnTypeSchema = true) })
	@PostMapping
	public ResponseEntity<ApiResponse<ConversionResp>> convert(
			@Parameter(description = DocExamples.MERCHANT_ID, example = DocExamples.MERCHANT) @PathVariable final UUID merchantId,
			@Parameter(description = DocExamples.IDEMPOTENCY_KEY, example = "convert-1")
			@RequestHeader("Idempotency-Key") @NotBlank(message = "{validation.idempotencyKey.required}")
			@Size(max = 128, message = "{validation.idempotencyKey.size}")
			@Pattern(regexp = ConversionReq.NO_CONTROL_CHARACTERS, message = "{validation.idempotencyKey.control}")
			final String idempotencyKey,
			@Valid @RequestBody final ConversionReq request) {
		ConversionResp conversion = conversions.accept(merchantId, idempotencyKey, request);
		HttpStatus status = COMPLETED.equals(conversion.status()) ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
		return ResponseEntity.status(status).body(new ApiResponse<>(conversion));
	}

	@Operation(operationId = "getConversion", summary = "Read a conversion back",
			description = "status COMPLETED, PENDING or REJECTED; a REJECTED conversion carries its reason.")
	@GetMapping("/{conversionId}")
	public ApiResponse<ConversionResp> find(
			@Parameter(description = DocExamples.MERCHANT_ID, example = DocExamples.MERCHANT) @PathVariable final UUID merchantId,
			@PathVariable final UUID conversionId) {
		return new ApiResponse<>(conversions.find(merchantId, conversionId));
	}

}
