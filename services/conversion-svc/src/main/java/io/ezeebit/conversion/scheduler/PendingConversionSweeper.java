package io.ezeebit.conversion.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.service.PendingResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Safe on every replica at once: rows are claimed with FOR UPDATE SKIP LOCKED; nothing is held between runs.
@Component
public class PendingConversionSweeper {

	private static final int MAX_PER_RUN = 100;

	private final PendingResolver resolver;

	public PendingConversionSweeper(final PendingResolver resolver) {
		this.resolver = resolver;
	}

	@Scheduled(fixedDelayString = "${conversion.sweep-interval}", initialDelayString = "${conversion.sweep-interval}")
	public void sweep() {
		// Rows left PENDING by an UNKNOWN answer are skipped for the rest of this run, not retried in a tight loop.
		List<UUID> attempted = new ArrayList<>();
		for (int i = 0; i < MAX_PER_RUN; i++) {
			Optional<UUID> resolved = resolver.resolveNext(attempted);
			if (resolved.isEmpty()) {
				return;
			}
			attempted.add(resolved.get());
		}
	}

}
