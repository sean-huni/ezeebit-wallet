package io.ezeebit.conversion.outbound.rates;

import org.jspecify.annotations.Nullable;

// The exchange-rate service's answer, owned by this caller. Contract (an assumption): ARCHITECTURE.md section 5.
public record RateDto(@Nullable String base, @Nullable String quote, @Nullable String rate, @Nullable String timestamp) {
}
