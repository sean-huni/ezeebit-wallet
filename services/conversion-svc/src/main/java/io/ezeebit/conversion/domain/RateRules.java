package io.ezeebit.conversion.domain;

import java.math.BigDecimal;
import java.time.Duration;

public record RateRules(Duration maxAge, BigDecimal maxJumpPercent) {
}
