package io.ezeebit.ledger.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc.ConversionPostingsBlockingStub;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc.ConversionSettlementBlockingStub;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.ledger.TestcontainersConfiguration;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.service.deposit.DepositService;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.ActiveProfiles;

// In-process transport against real Postgres: the server is the ledger's own, only the socket is skipped.
@SpringBootTest
@AutoConfigureTestGrpcTransport
@ActiveProfiles("acceptance")
@Import(TestcontainersConfiguration.class)
class ConversionPostingRpcIT {

	@Autowired
	private GrpcChannelFactory channels;

	@Autowired
	private DepositService deposits;

	private ManagedChannel channel;
	private ConversionPostingsBlockingStub postings;
	private ConversionSettlementBlockingStub settlement;
	private final UUID merchant = UUID.randomUUID();

	@BeforeEach
	void connect() {
		channel = channels.createChannel("ledger");
		postings = ConversionPostingsGrpc.newBlockingStub(channel);
		settlement = ConversionSettlementGrpc.newBlockingStub(channel);
		deposits.deposit("seed-%s".formatted(merchant), new DepositReq(merchant, "ZAR", "1000.00", "seed"));
	}

	@AfterEach
	void close() {
		channel.shutdownNow();
	}

	@Test
	void aPostingIsPostedWithBothAmounts() {
		PostingResult result = postings.post(request(UUID.randomUUID(), "184.32"));

		assertThat(result.getStatus()).isEqualTo(PostingStatus.POSTED);
		assertThat(result.getSellAmount()).isEqualTo("184.32");
		assertThat(result.getBuyAmount()).isEqualTo("10.000000");
		assertThat(result.getMerchantId()).isEqualTo(merchant.toString());
	}

	@Test
	void aRepeatedPostingReplaysTheSameResult() {
		PostingRequest request = request(UUID.randomUUID(), "184.32");

		assertThat(postings.post(request)).isEqualTo(postings.post(request));
	}

	@Test
	void anInsufficientFundsRefusalIsRefusedAgainOnRepeat() throws Exception {
		PostingRequest request = request(UUID.randomUUID(), "1000.01");

		assertRefused(() -> postings.post(request), Status.Code.FAILED_PRECONDITION, "INSUFFICIENT_FUNDS");
		assertRefused(() -> postings.post(request), Status.Code.FAILED_PRECONDITION, "INSUFFICIENT_FUNDS");
	}

	@Test
	void anAmountBeyondTheCurrencyScaleIsAnInvalidArgument() throws Exception {
		assertRefused(() -> postings.post(request(UUID.randomUUID(), "1.234")), Status.Code.INVALID_ARGUMENT,
				"AMOUNT_SCALE_EXCEEDED");
	}

	@Test
	void aConversionIdThatIsNoUuidFailsValidation() throws Exception {
		PostingRequest request = request(UUID.randomUUID(), "1.00").toBuilder().setConversionId("nope").build();

		assertRefused(() -> postings.post(request), Status.Code.INVALID_ARGUMENT, "VALIDATION_FAILED");
	}

	@Test
	void findingAnUnknownConversionIsNotFound() throws Exception {
		assertRefused(() -> settlement.find(lookup(UUID.randomUUID())), Status.Code.NOT_FOUND, "POSTING_NOT_FOUND");
	}

	@Test
	void aVoidBlocksALaterPosting() throws Exception {
		UUID conversion = UUID.randomUUID();

		assertThat(settlement.void_(lookup(conversion)).getStatus()).isEqualTo(PostingStatus.VOID);
		assertRefused(() -> postings.post(request(conversion, "184.32")), Status.Code.FAILED_PRECONDITION,
				"CONVERSION_VOIDED");
	}

	private PostingRequest request(final UUID conversion, final String sellAmount) {
		return PostingRequest.newBuilder().setConversionId(conversion.toString()).setMerchantId(merchant.toString())
				.setSellCurrency("ZAR").setSellAmount(sellAmount).setBuyCurrency("USDT").setBuyAmount("10.000000")
				.setDeadline(Instant.now().plus(Duration.ofMinutes(1)).toString()).build();
	}

	private PostingLookup lookup(final UUID conversion) {
		return PostingLookup.newBuilder().setConversionId(conversion.toString()).setMerchantId(merchant.toString()).build();
	}

	private static void assertRefused(final Runnable call, final Status.Code status, final String reason)
			throws InvalidProtocolBufferException {
		StatusRuntimeException ex = catchThrowableOfType(StatusRuntimeException.class, call::run);

		assertThat(ex).as("expected %s %s, but the call succeeded".formatted(status, reason)).isNotNull();
		assertThat(ex.getStatus().getCode()).isEqualTo(status);
		ErrorInfo info = StatusProto.fromThrowable(ex).getDetails(0).unpack(ErrorInfo.class);
		assertThat(info.getReason()).isEqualTo(reason);
	}

}
