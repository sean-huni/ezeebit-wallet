package io.ezeebit.gateway.domain;

// Every decision the edge limiter makes. CONTENTION is a healthy Redis losing compare-and-swap races on a hot key:
// answered 429 like LIMITED, and kept apart from UNAVAILABLE so it never pages as an outage.
public enum LimitOutcome { ALLOWED, LIMITED, CONTENTION, UNAVAILABLE, REFUSED_PATH }
