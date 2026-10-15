package io.ezeebit.ledger.grpc;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import jakarta.validation.Validator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

// The same bean validation the REST body had: the DTO's annotations stay the one home of the rules.
// proto3 has no absent string, so an empty one is read as missing and meets the DTO's @NotNull.
@Component
class PostingMessages {

	private final Validator validator;

	PostingMessages(final Validator validator) {
		this.validator = validator;
	}

	ConversionPostingReq toRequest(final PostingRequest message) {
		ConversionPostingReq request = new ConversionPostingReq(uuid(message.getConversionId(), "conversionId"),
				uuid(message.getMerchantId(), "merchantId"), present(message.getSellCurrency()),
				present(message.getSellAmount()), present(message.getBuyCurrency()), present(message.getBuyAmount()),
				instant(message.getDeadline()));
		validator.validate(request).stream().map(violation -> violation.getPropertyPath().toString())
				.min(Comparator.naturalOrder()).ifPresent(field -> {
					throw new LedgerException(ErrorCode.VALIDATION_FAILED, field);
				});
		return request;
	}

	UUID uuid(final String text, final String field) {
		try {
			return UUID.fromString(text);
		}
		catch (IllegalArgumentException ex) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, field);
		}
	}

	PostingResult toResult(final ConversionPostingResp resp) {
		PostingResult.Builder result = PostingResult.newBuilder().setJournalId(resp.journalId().toString())
				.setConversionId(resp.conversionId().toString()).setMerchantId(resp.merchantId().toString())
				.setStatus(PostingStatus.valueOf(resp.status())).setCreated(resp.created().toString());
		Optional.ofNullable(resp.reason()).ifPresent(result::setReason);
		Optional.ofNullable(resp.sellCurrency()).ifPresent(result::setSellCurrency);
		Optional.ofNullable(resp.sellAmount()).ifPresent(result::setSellAmount);
		Optional.ofNullable(resp.buyCurrency()).ifPresent(result::setBuyCurrency);
		Optional.ofNullable(resp.buyAmount()).ifPresent(result::setBuyAmount);
		return result.build();
	}

	private static @Nullable String present(final String text) {
		return text.isEmpty() ? null : text;
	}

	private static Instant instant(final String text) {
		try {
			return Instant.parse(text);
		}
		catch (DateTimeParseException ex) {
			throw new LedgerException(ErrorCode.VALIDATION_FAILED, "deadline");
		}
	}

}
