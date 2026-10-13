package io.ezeebit.conversion.domain;

import java.math.BigDecimal;

public record Priced(Money sell, Money buy, BigDecimal appliedRate) {
}
