package io.ezeebit.conversion.outbound.ledger;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.service.port.DeadLetter;
import io.ezeebit.conversion.service.port.DeadLetters;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Retry inside the breaker: one exhausted retry sequence is one breaker failure, and only transport failures count.
@Component
public class LedgerGuard {

	private static final Logger LOG = LoggerFactory.getLogger(LedgerGuard.class);

	private final Map<Operation, Retry> retries = new EnumMap<>(Operation.class);
	private final Map<Operation, CircuitBreaker> breakers = new EnumMap<>(Operation.class);
	private final DeadLetters deadLetters;

	// The status predicate cannot be bound from YAML, so each served instance is replaced by one that carries it.
	public LedgerGuard(final RetryRegistry retryRegistry, final CircuitBreakerRegistry breakerRegistry,
			final DeadLetters deadLetters) {
		this.deadLetters = deadLetters;
		for (Operation op : Operation.values()) {
			String name = op.instance();
			RetryConfig retry = RetryConfig.from(retryRegistry.retry(name).getRetryConfig())
					.retryOnException(LedgerGuard::isTransient).build();
			retries.put(op, Retry.of(name, retry));
			retryRegistry.replace(name, retries.get(op));
			CircuitBreakerConfig breaker = CircuitBreakerConfig.from(breakerRegistry.circuitBreaker(name)
					.getCircuitBreakerConfig()).recordException(LedgerGuard::isTransient).build();
			breakers.put(op, CircuitBreaker.of(name, breaker));
			breakerRegistry.replace(name, breakers.get(op));
		}
	}

	PostingResult call(final Operation op, final UUID conversionId, final UUID merchantId, final Message request,
			final LedgerGrpcClient.Attempt attempt) {
		AtomicInteger attempts = new AtomicInteger();
		Supplier<PostingResult> counted = () -> {
			attempts.incrementAndGet();
			return attempt.run();
		};
		try {
			return CircuitBreaker.decorateSupplier(breakers.get(op), Retry.decorateSupplier(retries.get(op), counted))
					.get();
		}
		catch (CallNotPermittedException ex) {
			record(op, conversionId, merchantId, request, attempts.get(), null, "CIRCUIT_OPEN");
			throw Status.UNAVAILABLE.withDescription("circuit open").asRuntimeException();
		}
		catch (StatusRuntimeException ex) {
			if (isTransient(ex)) {
				record(op, conversionId, merchantId, request, attempts.get(), ex, "RETRIES_EXHAUSTED");
			}
			throw ex;
		}
	}

	static boolean isTransient(final Throwable t) {
		return t instanceof StatusRuntimeException s && (s.getStatus().getCode() == Status.Code.UNAVAILABLE
				|| s.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED);
	}

	// A dead letter is an alert about a call that already failed; losing it must never change that call's outcome.
	private void record(final Operation op, final UUID conversionId, final UUID merchantId, final Message request,
			final int attempts, final StatusRuntimeException last, final String reason) {
		try {
			String lastStatus = last == null ? null : last.getStatus().getCode().name();
			String lastReason = last == null ? null
					: LedgerReplyMapper.reason(last).orElseGet(() -> last.getStatus().getDescription());
			deadLetters.record(new DeadLetter(op.name(), conversionId, merchantId, json(request), attempts, lastStatus,
					lastReason, reason, Instant.now(), Span.current().getSpanContext().getTraceId()));
		}
		catch (RuntimeException ex) {
			LOG.warn("ledger dead letter not recorded: op={} reason={} conversion={}: {}", op, reason, conversionId,
					LogText.oneLine(ex.getMessage()));
		}
	}

	private static String json(final Message request) {
		try {
			return JsonFormat.printer().print(request);
		}
		catch (InvalidProtocolBufferException ex) {
			return request.toString();
		}
	}

}
