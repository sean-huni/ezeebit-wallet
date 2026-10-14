package io.ezeebit.ledger.acceptance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.ledger.api.advice.ErrorStatus;
import io.ezeebit.ledger.exception.ErrorCode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

// Real gRPC on the loopback port the server bound. Each answer is rendered as the Reply the REST endpoint gave,
// status from ErrorStatus for the ErrorInfo reason, so the conversion scenarios read the same before and after.
public class LedgerRpc {

	private static final int CREATED = 201;
	private static final int OK = 200;

	private final Environment environment;
	private ManagedChannel channel;

	LedgerRpc(final Environment environment) {
		this.environment = environment;
	}

	public Reply post(final PostingRequest request) {
		return call(CREATED, () -> ConversionPostingsGrpc.newBlockingStub(channel()).post(request));
	}

	public Reply find(final PostingLookup lookup) {
		return call(OK, () -> ConversionSettlementGrpc.newBlockingStub(channel()).find(lookup));
	}

	public Reply voidPosting(final PostingLookup lookup) {
		return call(CREATED, () -> ConversionSettlementGrpc.newBlockingStub(channel()).void_(lookup));
	}

	public void close() {
		if (channel != null) {
			channel.shutdownNow();
		}
	}

	private static Reply call(final int success, final Supplier<PostingResult> rpc) {
		try {
			return new Reply(success, JsonMapper.shared().writeValueAsString(Map.of("data", data(rpc.get()))));
		}
		catch (StatusRuntimeException ex) {
			return refused(ex);
		}
	}

	private static Map<String, String> data(final PostingResult result) {
		Map<String, String> data = new LinkedHashMap<>();
		data.put("journalId", result.getJournalId());
		data.put("conversionId", result.getConversionId());
		data.put("merchantId", result.getMerchantId());
		data.put("status", result.getStatus().name());
		data.put("reason", result.getReason());
		data.put("sellCurrency", result.getSellCurrency());
		data.put("sellAmount", result.getSellAmount());
		data.put("buyCurrency", result.getBuyCurrency());
		data.put("buyAmount", result.getBuyAmount());
		data.put("created", result.getCreated());
		return data;
	}

	// A status with no ErrorInfo (UNAVAILABLE, UNIMPLEMENTED) is a broken harness or server, never a refusal.
	private static Reply refused(final StatusRuntimeException ex) {
		com.google.rpc.Status status = StatusProto.fromThrowable(ex);
		ErrorInfo info = status == null ? null : status.getDetailsList().stream().filter(any -> any.is(ErrorInfo.class))
				.findFirst().map(LedgerRpc::unpack).orElse(null);
		if (info == null) {
			throw ex;
		}
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("code", info.getReason());
		String field = info.getMetadataMap().get("field");
		if (field != null) {
			problem.put("errors", List.of(Map.of("field", field)));
		}
		int http = ErrorStatus.of(ErrorCode.valueOf(info.getReason())).value();
		return new Reply(http, JsonMapper.shared().writeValueAsString(problem));
	}

	private static ErrorInfo unpack(final Any any) {
		try {
			return any.unpack(ErrorInfo.class);
		}
		catch (InvalidProtocolBufferException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private synchronized ManagedChannel channel() {
		if (channel == null) {
			int port = Integer.parseInt(environment.getRequiredProperty("local.grpc.server.port"));
			channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
		}
		return channel;
	}

}
