package io.ezeebit.conversion.domain;

import java.util.Objects;

public record Currency(String code, int scale) {

	public Currency {
		Objects.requireNonNull(code, "code");
		if (scale < 0 || scale > 18) {
			throw new IllegalArgumentException("scale %d outside 0..18".formatted(scale));
		}
	}

}
