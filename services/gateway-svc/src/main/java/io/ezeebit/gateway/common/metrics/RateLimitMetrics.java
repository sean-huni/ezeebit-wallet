package io.ezeebit.gateway.common.metrics;

import java.util.function.Supplier;

import io.ezeebit.gateway.domain.Admission;
import io.ezeebit.gateway.domain.LimitOutcome;

public interface RateLimitMetrics {

	String NAME = "ezeebit.gateway.ratelimit";
	String DECISION = "ezeebit.ratelimit.decision";

	void record(LimitOutcome outcome);

	Admission decide(Supplier<Admission> decision);

}
