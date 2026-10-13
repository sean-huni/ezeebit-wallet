package io.ezeebit.ledger.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

@Component
public class AmountFormat {

	// UNNECESSARY: a stored amount always fits its currency's scale, so needing to round is a defect.
	public String format(final BigDecimal value, final int scale) {
		return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
	}

}
