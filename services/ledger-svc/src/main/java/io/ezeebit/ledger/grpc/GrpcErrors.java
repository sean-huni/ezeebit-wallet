package io.ezeebit.ledger.grpc;

import java.util.Set;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import io.ezeebit.ledger.common.log.LogText;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

// The code travels as ErrorInfo.reason so the caller decides on the code, never on the status alone.
@Component
public class GrpcErrors implements GrpcExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(GrpcErrors.class);
	static final String DOMAIN = "ledger.ezeebit.io";
	private static final Set<ErrorCode> REFUSALS = Set.of(ErrorCode.INSUFFICIENT_FUNDS, ErrorCode.BALANCE_LIMIT_EXCEEDED,
			ErrorCode.POSTING_DEADLINE_PASSED, ErrorCode.CONVERSION_VOIDED, ErrorCode.IDEMPOTENCY_KEY_REUSED);
	private static final Set<ErrorCode> INPUT = Set.of(ErrorCode.VALIDATION_FAILED, ErrorCode.MALFORMED_REQUEST,
			ErrorCode.UNKNOWN_CURRENCY, ErrorCode.SAME_CURRENCY, ErrorCode.AMOUNT_SCALE_EXCEEDED,
			ErrorCode.AMOUNT_NOT_POSITIVE, ErrorCode.AMOUNT_TOO_LARGE);

	@Override
	public StatusException handleException(final Throwable exception) {
		if (!(exception instanceof LedgerException ledger)) {
			LOG.error("unhandled exception", LogText.sanitised(exception));
			return Status.INTERNAL.asException();
		}
		Code grpcCode = code(ledger.code());
		if (grpcCode == Code.INTERNAL) {
			LOG.error("ledger invariant violated", LogText.sanitised(ledger));
		}
		ErrorInfo.Builder info = ErrorInfo.newBuilder().setReason(ledger.code().name()).setDomain(DOMAIN);
		if (ledger.field() != null) {
			info.putMetadata("field", ledger.field());
		}
		// Qualified: com.google.rpc.Status (the wire detail) clashes with io.grpc.Status.
		com.google.rpc.Status status = com.google.rpc.Status.newBuilder().setCode(grpcCode.getNumber())
				.setMessage(ledger.code().name()).addDetails(Any.pack(info.build())).build();
		return StatusProto.toStatusException(status);
	}

	private static Code code(final ErrorCode code) {
		if (REFUSALS.contains(code)) {
			return Code.FAILED_PRECONDITION;
		}
		if (INPUT.contains(code)) {
			return Code.INVALID_ARGUMENT;
		}
		return switch (code) {
			case CONVERSION_ALREADY_POSTED -> Code.ALREADY_EXISTS;
			case POSTING_NOT_FOUND -> Code.NOT_FOUND;
			default -> Code.INTERNAL;
		};
	}

}
