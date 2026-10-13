package io.ezeebit.conversion.acceptance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

// All tasks wait on one gate and are released together, so they genuinely overlap.
// Scenario-scoped state is bound to the step thread: tasks may only use values resolved before.
final class AtOnce {

	private AtOnce() {
	}

	static <T> List<T> run(final int count, final IntFunction<T> task) {
		CountDownLatch gate = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					gate.await();
					return task.apply(index);
				}));
			}
			gate.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		catch (Exception ex) {
			throw new IllegalStateException("concurrent tasks did not complete", ex);
		}
	}

}
