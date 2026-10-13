package io.ezeebit.conversion.service.sweep;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.ezeebit.conversion.config.properties.ConversionProperties;
import io.ezeebit.conversion.data.repo.ConversionRepo;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

// A level read from the database on every export, never from heap state: every replica reports the same
// number, so dashboards and alerts take max() of it, never sum().
@Component
public class PendingOverdueGauge implements MeterBinder {

	public static final String NAME = "ezeebit.conversion.pending.overdue";
	private static final long TIMEOUT_MILLIS = 2_000;

	private final ConversionRepo conversions;
	private final ConversionProperties properties;
	private final Clock clock;
	// The read runs here, not on the export thread: a database that does not answer would hold that thread for
	// the pool's 30 s connection timeout and delay every other metric. One read at a time; extra ones are dropped.
	private final ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(1), runnable -> Thread.ofPlatform().daemon().name("overdue-gauge").unstarted(runnable),
			new ThreadPoolExecutor.DiscardPolicy());

	public PendingOverdueGauge(final ConversionRepo conversions, final ConversionProperties properties,
			final Clock clock) {
		this.conversions = conversions;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void bindTo(final MeterRegistry registry) {
		Gauge.builder(NAME, this, PendingOverdueGauge::read)
				.description("PENDING conversions past deadline plus max transaction time, so due for the sweeper")
				.register(registry);
	}

	// NaN when the database cannot answer within about 2 s: an unknown level, never a guessed zero.
	public double read() {
		Future<Long> count = reader.submit(() -> conversions.countDue(clock.instant().minus(properties.maxTransactionTime())));
		try {
			return count.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException | ExecutionException ex) {
			count.cancel(true);
			return Double.NaN;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Double.NaN;
		}
	}

	@PreDestroy
	public void close() {
		reader.shutdownNow();
	}

}
