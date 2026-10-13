package io.ezeebit.conversion.domain;

import java.math.BigDecimal;

// The sanity band [min, max] a rate must fall in, and the spread kept by the platform.
public record PairPolicy(BigDecimal min, BigDecimal max, int spreadBps) {
}
