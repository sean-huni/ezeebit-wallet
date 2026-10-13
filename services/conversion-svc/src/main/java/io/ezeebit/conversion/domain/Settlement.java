package io.ezeebit.conversion.domain;

import org.jspecify.annotations.Nullable;

public record Settlement(ConversionStatus status, @Nullable RejectionReason reason) {
}
