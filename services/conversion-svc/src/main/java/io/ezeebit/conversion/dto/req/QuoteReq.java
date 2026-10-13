package io.ezeebit.conversion.dto.req;

import io.ezeebit.conversion.api.validation.ExactlyOneAmount;
import io.ezeebit.conversion.common.StrictStringDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

// Amounts are decimal strings, never JSON numbers; exactly one of them is given.
@ExactlyOneAmount
public record QuoteReq(
		@NotNull(message = "{validation.sell.required}") String sell,
		@NotNull(message = "{validation.buy.required}") String buy,
		@Nullable @JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DECIMAL, message = "{validation.amount.format}") String sellAmount,
		@Nullable @JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DECIMAL, message = "{validation.amount.format}") String buyAmount) {

	public static final String DECIMAL = "-?\\d{1,40}(\\.\\d{1,40})?";

}
