package io.ezeebit.ledger.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.google.rpc.ErrorInfo;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GrpcErrorsTest {

	@ParameterizedTest
	@CsvSource({ "INSUFFICIENT_FUNDS,FAILED_PRECONDITION", "BALANCE_LIMIT_EXCEEDED,FAILED_PRECONDITION",
			"POSTING_DEADLINE_PASSED,FAILED_PRECONDITION", "CONVERSION_VOIDED,FAILED_PRECONDITION",
			"IDEMPOTENCY_KEY_REUSED,FAILED_PRECONDITION", "CONVERSION_ALREADY_POSTED,ALREADY_EXISTS",
			"POSTING_NOT_FOUND,NOT_FOUND", "VALIDATION_FAILED,INVALID_ARGUMENT", "MALFORMED_REQUEST,INVALID_ARGUMENT",
			"UNKNOWN_CURRENCY,INVALID_ARGUMENT", "SAME_CURRENCY,INVALID_ARGUMENT", "AMOUNT_SCALE_EXCEEDED,INVALID_ARGUMENT",
			"AMOUNT_NOT_POSITIVE,INVALID_ARGUMENT", "AMOUNT_TOO_LARGE,INVALID_ARGUMENT", "CURRENCY_MISMATCH,INTERNAL",
			"UNBALANCED_POSTING,INTERNAL", "INTERNAL_ERROR,INTERNAL" })
	void eachCodeTravelsAsItsStatusAndReason(final ErrorCode code, final Status.Code status) throws Exception {
		StatusException ex = new GrpcErrors().handleException(new LedgerException(code, null));

		assertThat(ex.getStatus().getCode()).isEqualTo(status);
		ErrorInfo info = StatusProto.fromThrowable(ex).getDetails(0).unpack(ErrorInfo.class);
		assertThat(info.getReason()).isEqualTo(code.name());
		assertThat(info.getDomain()).isEqualTo("ledger.ezeebit.io");
		assertThat(info.getMetadataMap()).isEmpty();
	}

	@Test
	void theFieldAtFaultTravelsInTheMetadata() throws Exception {
		StatusException ex = new GrpcErrors().handleException(new LedgerException(ErrorCode.VALIDATION_FAILED, "merchantId"));

		ErrorInfo info = StatusProto.fromThrowable(ex).getDetails(0).unpack(ErrorInfo.class);
		assertThat(info.getMetadataMap()).containsExactly(Map.entry("field", "merchantId"));
	}

	@Test
	void anythingElseIsInternalAndSaysNothing() {
		StatusException ex = new GrpcErrors().handleException(new IllegalStateException("secret detail"));

		assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
		assertThat(ex.getStatus().getDescription()).isNull();
	}

}
