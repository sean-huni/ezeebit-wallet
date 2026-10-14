package io.ezeebit.conversion.outbound.ledger;

import java.util.Optional;
import java.util.Set;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.springframework.stereotype.Component;

// Translates the ledger's stored results and its gRPC refusals into this context's vocabulary.
@Component
public class LedgerReplyMapper {

	static final String LEDGER_DOMAIN = "ledger.ezeebit.io";

	// The ledger refuses these for the posting's content alone: the same message is refused every time.
	private static final Set<String> INPUT_REFUSALS = Set.of("VALIDATION_FAILED", "MALFORMED_REQUEST",
			"UNKNOWN_CURRENCY", "SAME_CURRENCY", "AMOUNT_SCALE_EXCEEDED", "AMOUNT_NOT_POSITIVE", "AMOUNT_TOO_LARGE");

	public LedgerAnswer fromResult(final PostingResult result) {
		return switch (result.getStatus()) {
			case POSTED -> LedgerAnswer.POSTED;
			case VOID -> LedgerAnswer.VOIDED;
			case REFUSED -> refusal(result.getReason());
			default -> LedgerAnswer.UNKNOWN;
		};
	}

	// IDEMPOTENCY_KEY_REUSED falls to UNKNOWN: something else holds the id, possibly a posting.
	public LedgerAnswer fromStatus(final StatusRuntimeException ex) {
		Optional<String> reason = reason(ex);
		if (reason.isEmpty()) {
			return LedgerAnswer.UNKNOWN;
		}
		String code = reason.get();
		if (ex.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
			return INPUT_REFUSALS.contains(code) ? LedgerAnswer.INPUT_REFUSED : LedgerAnswer.UNKNOWN;
		}
		return switch (code) {
			case "INSUFFICIENT_FUNDS", "BALANCE_LIMIT_EXCEEDED" -> refusal(code);
			case "POSTING_DEADLINE_PASSED" -> LedgerAnswer.DEADLINE_PASSED;
			case "CONVERSION_VOIDED" -> LedgerAnswer.VOIDED;
			case "CONVERSION_ALREADY_POSTED" -> LedgerAnswer.POSTED;
			case "POSTING_NOT_FOUND" -> LedgerAnswer.ABSENT;
			default -> LedgerAnswer.UNKNOWN;
		};
	}

	private static LedgerAnswer refusal(final String reason) {
		return switch (reason) {
			case "INSUFFICIENT_FUNDS" -> LedgerAnswer.INSUFFICIENT_FUNDS;
			case "BALANCE_LIMIT_EXCEEDED" -> LedgerAnswer.BALANCE_LIMIT_EXCEEDED;
			default -> LedgerAnswer.UNKNOWN;
		};
	}

	// Only the ledger's own ErrorInfo decides an outcome; a proxy or a library can raise the same status codes.
	private static Optional<String> reason(final StatusRuntimeException ex) {
		com.google.rpc.Status status = StatusProto.fromThrowable(ex);
		if (status == null) {
			return Optional.empty();
		}
		return status.getDetailsList().stream().filter(detail -> detail.is(ErrorInfo.class))
				.map(LedgerReplyMapper::errorInfo).flatMap(Optional::stream)
				.filter(info -> LEDGER_DOMAIN.equals(info.getDomain())).findFirst().map(ErrorInfo::getReason);
	}

	private static Optional<ErrorInfo> errorInfo(final Any detail) {
		try {
			return Optional.of(detail.unpack(ErrorInfo.class));
		}
		catch (InvalidProtocolBufferException ex) {
			return Optional.empty();
		}
	}

}
