package io.ezeebit.conversion.acceptance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

// Forwards conversion-svc's gRPC calls to the real ledger and injects faults on Post; the ledger is never altered.
final class LedgerFaultProxy {

	private static final Metadata.Key<String> TRACEPARENT = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

	record Call(String method, String conversionId, String traceparent) {
	}

	record Fault(Mode mode, Duration delay, Status.Code code, String reason) {

		enum Mode { NEVER_FORWARD, ANSWER_LATE, REFUSE }

		// Held past the caller's deadline and never forwarded: the posting never reaches the ledger.
		static Fault unanswered(final Duration hold) {
			return new Fault(Mode.NEVER_FORWARD, hold, Status.Code.DEADLINE_EXCEEDED, "");
		}

		// Forwarded at once, the ledger's answer held back: the posting commits before the caller hears of it.
		static Fault answeredLate(final Duration delay) {
			return new Fault(Mode.ANSWER_LATE, delay, Status.Code.OK, "");
		}

		static Fault refuse(final Status.Code code, final String reason) {
			return new Fault(Mode.REFUSE, Duration.ZERO, code, reason);
		}

	}

	private final ManagedChannel ledger;
	private final ConcurrentLinkedQueue<Call> journal = new ConcurrentLinkedQueue<>();
	private final Map<String, PostingRequest> posted = new ConcurrentHashMap<>();
	private final AtomicReference<List<Fault>> faults = new AtomicReference<>(List.of());
	private final AtomicInteger faultedPosts = new AtomicInteger();
	private final AtomicInteger postsAtClear = new AtomicInteger();
	private final Context.Key<String> traceparent = Context.key("traceparent");
	private Server server;

	LedgerFaultProxy(final String ledgerHost, final int ledgerGrpcPort) {
		ledger = Grpc.newChannelBuilderForAddress(ledgerHost, ledgerGrpcPort, InsecureChannelCredentials.create()).build();
	}

	void start() throws IOException {
		ServerInterceptor traces = new ServerInterceptor() {
			@Override
			public <Q, R> ServerCall.Listener<Q> interceptCall(final ServerCall<Q, R> call, final Metadata headers,
					final ServerCallHandler<Q, R> next) {
				return Contexts.interceptCall(Context.current().withValue(traceparent, headers.get(TRACEPARENT)), call,
						headers, next);
			}
		};
		server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0), InsecureServerCredentials.create())
				.addService(ServerInterceptors.intercept(new Postings(), traces))
				.addService(ServerInterceptors.intercept(new Settlement(), traces)).build().start();
	}

	int port() {
		return server.getPort();
	}

	void stop() {
		server.shutdownNow();
		ledger.shutdownNow();
	}

	void clearFaults() {
		faults.set(List.of());
		postsAtClear.set(calls("Post"));
	}

	void reset() {
		faults.set(List.of());
		journal.clear();
		posted.clear();
		postsAtClear.set(0);
	}

	void post(final Fault fault) {
		posts(fault, fault);
	}

	void posts(final Fault first, final Fault later) {
		faultedPosts.set(0);
		faults.set(List.of(first, later));
	}

	int calls(final String method) {
		return (int) journal.stream().filter(call -> call.method().equals(method)).count();
	}

	int calls(final String method, final String conversionId) {
		return (int) journal.stream().filter(call -> call.method().equals(method) && call.conversionId().equals(conversionId))
				.count();
	}

	List<String> traceparents(final String method) {
		return journal.stream().filter(call -> call.method().equals(method) && call.traceparent() != null)
				.map(Call::traceparent).toList();
	}

	int postsSinceClear() {
		return calls("Post") - postsAtClear.get();
	}

	Optional<PostingRequest> posting(final String conversionId) {
		return Optional.ofNullable(posted.get(conversionId));
	}

	private void record(final String method, final String conversionId) {
		journal.add(new Call(method, conversionId, traceparent.get()));
	}

	private final class Postings extends ConversionPostingsGrpc.ConversionPostingsImplBase {

		@Override
		public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
			record("Post", request.getConversionId());
			posted.put(request.getConversionId(), request);
			List<Fault> active = faults.get();
			Optional<Fault> fault = active.isEmpty() ? Optional.empty()
					: Optional.of(active.get(Math.min(faultedPosts.getAndIncrement(), active.size() - 1)));
			apply(fault, () -> ConversionPostingsGrpc.newBlockingStub(ledger).post(request), reply);
		}

	}

	private final class Settlement extends ConversionSettlementGrpc.ConversionSettlementImplBase {

		@Override
		public void find(final PostingLookup request, final StreamObserver<PostingResult> reply) {
			record("Find", request.getConversionId());
			apply(Optional.empty(), () -> ConversionSettlementGrpc.newBlockingStub(ledger).find(request), reply);
		}

		@Override
		public void void_(final PostingLookup request, final StreamObserver<PostingResult> reply) {
			record("Void", request.getConversionId());
			apply(Optional.empty(), () -> ConversionSettlementGrpc.newBlockingStub(ledger).void_(request), reply);
		}

	}

	private static void apply(final Optional<Fault> fault, final Supplier<PostingResult> forward,
			final StreamObserver<PostingResult> reply) {
		Fault.Mode mode = fault.map(Fault::mode).orElse(null);
		if (mode == Fault.Mode.REFUSE) {
			reply.onError(refusal(fault.get()));
			return;
		}
		if (mode == Fault.Mode.NEVER_FORWARD) {
			pause(fault.get().delay());
			answer(reply, () -> reply.onError(Status.DEADLINE_EXCEEDED.asRuntimeException()));
			return;
		}
		PostingResult result;
		try {
			result = forward.get();
		}
		catch (StatusRuntimeException ledgerError) {
			fault.ifPresent(late -> pause(late.delay()));
			answer(reply, () -> reply.onError(ledgerError.getStatus().asRuntimeException(ledgerError.getTrailers())));
			return;
		}
		fault.ifPresent(late -> pause(late.delay()));
		answer(reply, () -> {
			reply.onNext(result);
			reply.onCompleted();
		});
	}

	// A caller whose deadline passed has gone; answering it would throw.
	private static void answer(final StreamObserver<PostingResult> reply, final Runnable send) {
		if (!((ServerCallStreamObserver<PostingResult>) reply).isCancelled()) {
			send.run();
		}
	}

	private static StatusRuntimeException refusal(final Fault fault) {
		ErrorInfo info = ErrorInfo.newBuilder().setReason(fault.reason()).setDomain("ledger.ezeebit.io").build();
		return StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder().setCode(fault.code().value())
				.setMessage(fault.reason()).addDetails(Any.pack(info)).build());
	}

	private static void pause(final Duration delay) {
		try {
			TimeUnit.MILLISECONDS.sleep(delay.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
