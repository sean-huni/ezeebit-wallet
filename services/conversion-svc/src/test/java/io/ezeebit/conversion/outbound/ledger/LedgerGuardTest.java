package io.ezeebit.conversion.outbound.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.contracts.ledger.v1.PostingStatus;
import io.ezeebit.conversion.domain.Currency;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.Money;
import io.ezeebit.conversion.domain.Quote;
import io.ezeebit.conversion.outbound.discovery.DiscoveryNameResolverProvider;
import io.ezeebit.conversion.service.port.DeadLetter;
import io.ezeebit.conversion.service.port.DeadLetters;
import io.ezeebit.conversion.service.port.LedgerPosting;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.simple.InstanceProperties;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;

class LedgerGuardTest {

	private static final UUID CONVERSION = UUID.fromString("0b6f2b0e-6f3f-4c55-9a43-8f8a3c0e2d11");
	private static final UUID MERCHANT = UUID.fromString("7d3c4f1a-2b9e-4e6a-8c1d-5a0f9e8b7c62");
	private static final PostingRequest REQUEST = PostingRequest.newBuilder().setConversionId(CONVERSION.toString())
			.setMerchantId(MERCHANT.toString()).build();
	private static final PostingResult POSTED = PostingResult.newBuilder().setStatus(PostingStatus.POSTED).build();
	// The served values (conversion-svc.yml); FAST keeps the shape and shrinks only the backoff.
	private static final Duration POST_WAIT = Duration.ofMillis(200);
	private static final Duration FAST = Duration.ofMillis(1);

	private final List<DeadLetter> letters = new CopyOnWriteArrayList<>();
	private final AtomicInteger attempts = new AtomicInteger();

	@Test
	void aTransientFailureIsRetriedUntilTheLedgerAnswers() {
		LedgerGuard guard = guard(FAST, letters::add);

		PostingResult result = guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST, () -> {
			if (attempts.incrementAndGet() < 3) {
				throw Status.UNAVAILABLE.asRuntimeException();
			}
			return POSTED;
		});

		assertThat(result).isEqualTo(POSTED);
		assertThat(attempts).hasValue(3);
		assertThat(letters).isEmpty();
	}

	@Test
	void exhaustedRetriesRethrowTheLastFailureAndRecordOneDeadLetter() {
		LedgerGuard guard = guard(FAST, letters::add);

		assertThatThrownBy(() -> guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST, failing(Status.UNAVAILABLE)))
				.isInstanceOfSatisfying(StatusRuntimeException.class,
						ex -> assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));

		assertThat(attempts).hasValue(3);
		assertThat(letters).singleElement().satisfies(letter -> {
			assertThat(letter.op()).isEqualTo("POST");
			assertThat(letter.reason()).isEqualTo("RETRIES_EXHAUSTED");
			assertThat(letter.lastStatus()).isEqualTo("UNAVAILABLE");
			assertThat(letter.attempts()).isEqualTo(3);
			assertThat(letter.conversionId()).isEqualTo(CONVERSION);
			assertThat(letter.merchantId()).isEqualTo(MERCHANT);
			assertThat(letter.request()).contains(CONVERSION.toString());
		});
	}

	@Test
	void aLedgerRefusalIsFinalAndNeverRetried() {
		LedgerGuard guard = guard(FAST, letters::add);

		assertThatThrownBy(() -> guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST,
				failing(Status.FAILED_PRECONDITION))).isInstanceOfSatisfying(StatusRuntimeException.class,
						ex -> assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));

		assertThat(attempts).hasValue(1);
		assertThat(letters).isEmpty();
	}

	@Test
	void twentyExhaustedCallsOpenTheBreakerAndTheNextIsRefusedWithoutReachingTheLedger() {
		LedgerGuard guard = guard(FAST, letters::add);
		for (int i = 0; i < 20; i++) {
			assertThatThrownBy(() -> guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST, failing(Status.UNAVAILABLE)))
					.isInstanceOf(StatusRuntimeException.class);
		}
		letters.clear();
		int before = attempts.get();

		assertThatThrownBy(() -> guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST, failing(Status.UNAVAILABLE)))
				.isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
					assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
					assertThat(ex.getStatus().getDescription()).isEqualTo("circuit open");
				});

		assertThat(attempts.get() - before).as("attempts made through an open breaker").isZero();
		assertThat(letters).singleElement().satisfies(letter -> {
			assertThat(letter.reason()).isEqualTo("CIRCUIT_OPEN");
			assertThat(letter.op()).isEqualTo("POST");
		});
	}

	// A refusal is the ledger working: only transport failures may open the breaker in front of every merchant.
	@Test
	void refusalsNeverOpenTheBreaker() {
		LedgerGuard guard = guard(FAST, letters::add);
		for (int i = 0; i < 25; i++) {
			assertThatThrownBy(() -> guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST,
					failing(Status.FAILED_PRECONDITION))).isInstanceOf(StatusRuntimeException.class);
		}

		assertThat(attempts).hasValue(25);
		assertThat(letters).isEmpty();
	}

	@Test
	void eachOperationHasItsOwnBreaker() {
		LedgerGuard guard = guard(FAST, letters::add);
		for (int i = 0; i < 20; i++) {
			assertThatThrownBy(() -> guard.call(Operation.VOID, CONVERSION, MERCHANT, REQUEST, failing(Status.UNAVAILABLE)))
					.isInstanceOf(StatusRuntimeException.class);
		}

		assertThat(guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST, () -> POSTED)).isEqualTo(POSTED);
	}

	@Test
	void aFailingDeadLetterSinkNeverChangesTheOutcome() {
		LedgerGuard guard = guard(FAST, letter -> {
			throw new IllegalStateException("sink down");
		});

		assertThatThrownBy(() -> guard.call(Operation.FIND, CONVERSION, MERCHANT, REQUEST, failing(Status.UNAVAILABLE)))
				.isInstanceOfSatisfying(StatusRuntimeException.class,
						ex -> assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
	}

	// Budget: 3 attempts of 2 s plus 200 ms and 400 ms of backoff is 6.6 s, inside the gateway's 10 s.
	@Test
	void theWorstCasePostStaysInsideItsBudget() {
		LedgerGuard guard = guard(POST_WAIT, letters::add);
		long start = System.nanoTime();

		assertThatThrownBy(() -> guard.call(Operation.POST, CONVERSION, MERCHANT, REQUEST, () -> {
			attempts.incrementAndGet();
			sleep(Duration.ofSeconds(2));
			throw Status.DEADLINE_EXCEEDED.asRuntimeException();
		})).isInstanceOf(StatusRuntimeException.class);

		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
		assertThat(attempts).hasValue(3);
		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(6_600)).isLessThan(Duration.ofSeconds(7));
		assertThat(letters).singleElement().extracting(DeadLetter::lastStatus).isEqualTo("DEADLINE_EXCEEDED");
	}

	// An abrupt kill races the channel's GOAWAY: the first call after it can fail UNAVAILABLE, and the retry absorbs it.
	@Test
	void anAbruptlyKilledInstanceLosesNoPosting() throws Exception {
		AtomicInteger servedByOne = new AtomicInteger();
		AtomicInteger servedByTwo = new AtomicInteger();
		Server one = ServerBuilder.forPort(0).addService(posting(servedByOne)).build().start();
		Server two = ServerBuilder.forPort(0).addService(posting(servedByTwo)).build().start();
		DiscoveryNameResolverProvider provider = new DiscoveryNameResolverProvider(discovery(one.getPort(), two.getPort()));
		ManagedChannel channel = ManagedChannelBuilder.forTarget("discovery:///ledger-svc").usePlaintext()
				.defaultLoadBalancingPolicy("round_robin").build();
		try {
			LedgerGrpcClient client = new LedgerGrpcClient(channel, new LedgerReplyMapper(), guard(POST_WAIT, letters::add));
			for (int i = 0; i < 10; i++) {
				assertThat(client.post(posting())).isEqualTo(LedgerAnswer.POSTED);
			}
			assertThat(servedByOne).as("instance one served before the kill").hasPositiveValue();

			one.shutdownNow();
			int before = servedByTwo.get();
			for (int i = 0; i < 5; i++) {
				assertThat(client.post(posting())).as("post %d after the kill".formatted(i)).isEqualTo(LedgerAnswer.POSTED);
			}

			assertThat(servedByTwo.get() - before).isEqualTo(5);
			assertThat(letters).isEmpty();
		}
		finally {
			provider.destroy();
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
			one.shutdownNow();
			two.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private LedgerGrpcClient.Attempt failing(final Status status) {
		return () -> {
			attempts.incrementAndGet();
			throw status.asRuntimeException();
		};
	}

	static LedgerGuard guard(final Duration postWait, final DeadLetters deadLetters) {
		RetryRegistry retries = RetryRegistry.ofDefaults();
		retries.retry(Operation.POST.instance(), retry(postWait));
		retries.retry(Operation.FIND.instance(), retry(postWait.multipliedBy(5).dividedBy(2)));
		retries.retry(Operation.VOID.instance(), retry(postWait.multipliedBy(5).dividedBy(2)));
		CircuitBreakerConfig ledger = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).slidingWindowSize(20)
				.minimumNumberOfCalls(20).failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(10))
				.permittedNumberOfCallsInHalfOpenState(3).build();
		CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(Map.of("ledger", ledger));
		for (Operation op : Operation.values()) {
			breakers.circuitBreaker(op.instance(), ledger);
		}
		return new LedgerGuard(retries, breakers, deadLetters);
	}

	private static RetryConfig retry(final Duration wait) {
		return RetryConfig.custom().maxAttempts(3).intervalFunction(IntervalFunction.ofExponentialBackoff(wait, 2)).build();
	}

	private static LedgerPosting posting() {
		Currency usdt = new Currency("USDT", 6);
		Currency zar = new Currency("ZAR", 2);
		Quote quote = new Quote(UUID.randomUUID(), MERCHANT, Money.parse("10", usdt), Money.parse("183.39", zar),
				new BigDecimal("18.4321"), new BigDecimal("18.3399395"), 50, Instant.parse("2026-10-14T09:59:58Z"),
				Instant.parse("2026-10-14T10:00:15Z"));
		return new LedgerPosting(UUID.randomUUID(), MERCHANT, quote, Instant.parse("2026-10-14T10:00:45Z"));
	}

	private static ConversionPostingsGrpc.ConversionPostingsImplBase posting(final AtomicInteger served) {
		return new ConversionPostingsGrpc.ConversionPostingsImplBase() {
			@Override
			public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
				served.incrementAndGet();
				reply.onNext(POSTED);
				reply.onCompleted();
			}
		};
	}

	private static SimpleDiscoveryClient discovery(final int... grpcPorts) {
		List<InstanceProperties> instances = Arrays.stream(grpcPorts).mapToObj(port -> {
			InstanceProperties instance = new InstanceProperties();
			instance.setInstanceId("ledger-%d".formatted(port));
			instance.setServiceId("ledger-svc");
			instance.setHost("127.0.0.1");
			instance.setPort(1);
			instance.setMetadata(Map.of("grpcPort", String.valueOf(port)));
			return instance;
		}).toList();
		SimpleDiscoveryProperties properties = new SimpleDiscoveryProperties();
		properties.setInstances(Map.of("ledger-svc", instances));
		return new SimpleDiscoveryClient(properties);
	}

	private static void sleep(final Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
