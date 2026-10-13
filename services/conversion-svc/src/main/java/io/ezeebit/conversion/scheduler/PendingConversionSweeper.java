package io.ezeebit.conversion.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.common.metrics.ConversionSpans;
import io.ezeebit.conversion.service.sweep.PendingResolver;
import io.ezeebit.conversion.service.sweep.PendingResolver.Swept;
import io.micrometer.observation.Observation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Safe on every replica at once: rows are claimed with FOR UPDATE SKIP LOCKED; nothing is held between runs.
@Component
public class PendingConversionSweeper {

	private static final int MAX_PER_RUN = 100;

	private final PendingResolver resolver;
	private final ConversionSpans spans;

	public PendingConversionSweeper(final PendingResolver resolver, final ConversionMetrics metrics) {
		this.resolver = resolver;
		this.spans = metrics.spans();
	}

	@Scheduled(fixedDelayString = "${conversion.sweep-interval}", initialDelayString = "${conversion.sweep-interval}")
	public void sweep() {
		Observation span = spans.sweep();
		spans.observe(span, () -> {
			spans.swept(span, run());
			return null;
		});
	}

	// Rows left PENDING by an UNKNOWN answer are skipped for the rest of this run, not retried in a tight loop.
	private Map<String, Integer> run() {
		Map<String, Integer> actions = new TreeMap<>();
		List<UUID> attempted = new ArrayList<>();
		for (int i = 0; i < MAX_PER_RUN; i++) {
			Optional<Swept> swept = resolver.resolveNext(attempted);
			if (swept.isEmpty()) {
				break;
			}
			attempted.add(swept.get().id());
			actions.merge(swept.get().action(), 1, Integer::sum);
		}
		return actions;
	}

}
