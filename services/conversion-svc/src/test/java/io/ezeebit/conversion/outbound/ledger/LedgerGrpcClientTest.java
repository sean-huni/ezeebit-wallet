package io.ezeebit.conversion.outbound.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.Money;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.service.port.LedgerPosting;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerGrpcClientTest {

	private static final UUID CONVERSION = UUID.fromString("0b6f2b0e-6f3f-4c55-9a43-8f8a3c0e2d11");
	private static final UUID MERCHANT = UUID.fromString("7d3c4f1a-2b9e-4e6a-8c1d-5a0f9e8b7c62");
	private static final Instant DEADLINE = Instant.parse("2026-10-14T10:00:45Z");

	private final FakeLedger ledger = new FakeLedger();
	private Server server;
	private ManagedChannel channel;
	private LedgerGrpcClient client;

	@BeforeEach
	void start() throws Exception {
		String name = InProcessServerBuilder.generateName();
		server = InProcessServerBuilder.forName(name).directExecutor().addService(ledger.postings())
				.addService(ledger.settlement()).build().start();
		channel = InProcessChannelBuilder.forName(name).directExecutor().build();
		client = new LedgerGrpcClient(channel, new LedgerReplyMapper());
	}

	@AfterEach
	void stop() throws Exception {
		channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void aPostSendsEveryFieldOfThePostingAndMapsTheReply() {
		ledger.answer = PostingResult.newBuilder().setStatus(PostingStatus.POSTED).build();

		LedgerAnswer answer = client.post(new LedgerPosting(CONVERSION, MERCHANT, quote(), DEADLINE));

		assertThat(answer).isEqualTo(LedgerAnswer.POSTED);
		assertThat(ledger.received).containsExactly(new Received("post", PostingRequest.newBuilder().setConversionId(CONVERSION.toString())
				.setMerchantId(MERCHANT.toString()).setSellCurrency("USDT").setSellAmount("10.000000")
				.setBuyCurrency("ZAR").setBuyAmount("183.39").setDeadline("2026-10-14T10:00:45Z").build()));
	}

	@Test
	void aLookupSendsBothIdsAndMapsTheStoredResult() {
		ledger.answer = PostingResult.newBuilder().setStatus(PostingStatus.VOID).build();

		assertThat(client.lookup(CONVERSION, MERCHANT)).isEqualTo(LedgerAnswer.VOIDED);
		assertThat(ledger.received).containsExactly(lookup("find"));
	}

	@Test
	void aVoidSendsBothIdsAndMapsTheStoredResult() {
		ledger.answer = PostingResult.newBuilder().setStatus(PostingStatus.VOID).build();

		assertThat(client.voidPosting(CONVERSION, MERCHANT)).isEqualTo(LedgerAnswer.VOIDED);
		assertThat(ledger.received).containsExactly(lookup("void"));
	}

	@Test
	void anUnavailableLedgerLeavesTheOutcomeUnknown() {
		ledger.failure = Status.UNAVAILABLE.withDescription("ledger down").asRuntimeException();

		assertThat(client.post(new LedgerPosting(CONVERSION, MERCHANT, quote(), DEADLINE)))
				.isEqualTo(LedgerAnswer.UNKNOWN);
		assertThat(client.lookup(CONVERSION, MERCHANT)).isEqualTo(LedgerAnswer.UNKNOWN);
		assertThat(client.voidPosting(CONVERSION, MERCHANT)).isEqualTo(LedgerAnswer.UNKNOWN);
		assertThat(ledger.received).extracting(Received::operation).containsExactly("post", "find", "void");
	}

	// A refused lookup or void is about this caller's message, never the conversion: the posting may exist.
	@Test
	void anInputRefusalOfALookupOrVoidLeavesTheOutcomeOpen() {
		ledger.failure = StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
				.setCode(Code.INVALID_ARGUMENT.getNumber()).addDetails(Any.pack(ErrorInfo.newBuilder()
						.setReason("MALFORMED_REQUEST").setDomain(LedgerReplyMapper.LEDGER_DOMAIN).build()))
				.build());

		assertThat(client.post(new LedgerPosting(CONVERSION, MERCHANT, quote(), DEADLINE)))
				.isEqualTo(LedgerAnswer.INPUT_REFUSED);
		assertThat(client.lookup(CONVERSION, MERCHANT)).isEqualTo(LedgerAnswer.UNKNOWN);
		assertThat(client.voidPosting(CONVERSION, MERCHANT)).isEqualTo(LedgerAnswer.UNKNOWN);
	}

	private static Received lookup(final String operation) {
		return new Received(operation, PostingLookup.newBuilder().setConversionId(CONVERSION.toString())
				.setMerchantId(MERCHANT.toString()).build());
	}

	private static Quote quote() {
		Currency usdt = new Currency("USDT", 6);
		Currency zar = new Currency("ZAR", 2);
		return new Quote(UUID.randomUUID(), MERCHANT, Money.parse("10", usdt), Money.parse("183.39", zar),
				new BigDecimal("18.4321"), new BigDecimal("18.3399395"), 50, Instant.parse("2026-10-14T09:59:58Z"),
				Instant.parse("2026-10-14T10:00:15Z"));
	}

	private record Received(String operation, Message message) {
	}

	private static final class FakeLedger {

		private final List<Received> received = new ArrayList<>();
		private PostingResult answer = PostingResult.getDefaultInstance();
		private StatusRuntimeException failure;

		ConversionPostingsGrpc.ConversionPostingsImplBase postings() {
			return new ConversionPostingsGrpc.ConversionPostingsImplBase() {

				@Override
				public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
					answer("post", request, reply);
				}

			};
		}

		ConversionSettlementGrpc.ConversionSettlementImplBase settlement() {
			return new ConversionSettlementGrpc.ConversionSettlementImplBase() {

				@Override
				public void find(final PostingLookup request, final StreamObserver<PostingResult> reply) {
					answer("find", request, reply);
				}

				@Override
				public void void_(final PostingLookup request, final StreamObserver<PostingResult> reply) {
					answer("void", request, reply);
				}

			};
		}

		private void answer(final String operation, final Message request, final StreamObserver<PostingResult> reply) {
			received.add(new Received(operation, request));
			if (failure != null) {
				reply.onError(failure);
				return;
			}
			reply.onNext(answer);
			reply.onCompleted();
		}

	}

}
