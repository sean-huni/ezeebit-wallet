package io.ezeebit.conversion.outbound.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerReplyMapperTest {

	private final LedgerReplyMapper mapper = new LedgerReplyMapper();

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = {
			"POSTED, null, POSTED",
			"VOID, null, VOIDED",
			"REFUSED, INSUFFICIENT_FUNDS, INSUFFICIENT_FUNDS",
			"REFUSED, BALANCE_LIMIT_EXCEEDED, BALANCE_LIMIT_EXCEEDED",
			"REFUSED, OTHER, UNKNOWN",
			"POSTING_STATUS_UNSPECIFIED, null, UNKNOWN" })
	void eachStoredResultMeansOneThing(final PostingStatus status, final String reason, final LedgerAnswer answer) {
		PostingResult.Builder result = PostingResult.newBuilder().setStatus(status);
		if (reason != null) {
			result.setReason(reason);
		}

		assertThat(mapper.fromResult(result.build())).isEqualTo(answer);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = {
			"FAILED_PRECONDITION, INSUFFICIENT_FUNDS, INSUFFICIENT_FUNDS",
			"FAILED_PRECONDITION, BALANCE_LIMIT_EXCEEDED, BALANCE_LIMIT_EXCEEDED",
			"FAILED_PRECONDITION, POSTING_DEADLINE_PASSED, DEADLINE_PASSED",
			"FAILED_PRECONDITION, CONVERSION_VOIDED, VOIDED",
			"FAILED_PRECONDITION, IDEMPOTENCY_KEY_REUSED, UNKNOWN",
			"ALREADY_EXISTS, CONVERSION_ALREADY_POSTED, POSTED",
			"NOT_FOUND, POSTING_NOT_FOUND, ABSENT",
			"NOT_FOUND, null, UNKNOWN",
			"INVALID_ARGUMENT, VALIDATION_FAILED, INPUT_REFUSED",
			"INVALID_ARGUMENT, MALFORMED_REQUEST, INPUT_REFUSED",
			"INVALID_ARGUMENT, UNKNOWN_CURRENCY, INPUT_REFUSED",
			"INVALID_ARGUMENT, SAME_CURRENCY, INPUT_REFUSED",
			"INVALID_ARGUMENT, AMOUNT_SCALE_EXCEEDED, INPUT_REFUSED",
			"INVALID_ARGUMENT, AMOUNT_NOT_POSITIVE, INPUT_REFUSED",
			"INVALID_ARGUMENT, AMOUNT_TOO_LARGE, INPUT_REFUSED",
			"INTERNAL, null, UNKNOWN",
			"UNAVAILABLE, null, UNKNOWN",
			"DEADLINE_EXCEEDED, null, UNKNOWN" })
	void eachStatusMeansOneThing(final Code code, final String reason, final LedgerAnswer answer) {
		assertThat(mapper.fromStatus(failure(code, reason))).isEqualTo(answer);
	}

	@ParameterizedTest
	@CsvSource({ "UNAVAILABLE", "DEADLINE_EXCEEDED", "NOT_FOUND" })
	void aStatusWithNoRichDetailIsUnknown(final Status.Code code) {
		assertThat(mapper.fromStatus(Status.fromCode(code).asRuntimeException())).isEqualTo(LedgerAnswer.UNKNOWN);
	}

	@ParameterizedTest
	@CsvSource({ "FAILED_PRECONDITION, INSUFFICIENT_FUNDS", "NOT_FOUND, POSTING_NOT_FOUND" })
	void aReasonFromAnotherDomainDecidesNothing(final Code code, final String reason) {
		com.google.rpc.Status status = com.google.rpc.Status.newBuilder().setCode(code.getNumber())
				.addDetails(Any.pack(ErrorInfo.newBuilder().setReason(reason).setDomain("proxy.example").build())).build();

		assertThat(mapper.fromStatus(StatusProto.toStatusRuntimeException(status))).isEqualTo(LedgerAnswer.UNKNOWN);
	}

	private static StatusRuntimeException failure(final Code code, final String reason) {
		com.google.rpc.Status.Builder status = com.google.rpc.Status.newBuilder().setCode(code.getNumber());
		if (reason != null) {
			status.addDetails(Any.pack(ErrorInfo.newBuilder().setReason(reason).setDomain("ledger.ezeebit.io").build()));
		}
		return StatusProto.toStatusRuntimeException(status.build());
	}

}
