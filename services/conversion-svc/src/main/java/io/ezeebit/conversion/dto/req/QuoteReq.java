package io.ezeebit.conversion.dto.req;

import io.ezeebit.conversion.api.json.StrictStringDeserializer;
import io.ezeebit.conversion.api.validation.ExactlyOneAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

// Amounts are decimal strings, never JSON numbers; exactly one of them is given.
@ExactlyOneAmount
@Schema(example = "{\"sell\":\"ZAR\",\"buy\":\"USDT\",\"sellAmount\":\"1000.00\"}")
public record QuoteReq(
		@Schema(description = "Currency that leaves the merchant.", example = "ZAR")
		@NotNull(message = "{validation.sell.required}") String sell,
		@Schema(description = "Currency that arrives.", example = "USDT")
		@NotNull(message = "{validation.buy.required}") String buy,
		@Schema(description = "Exact amount to sell, a decimal string. Give this or buyAmount.", example = "1000.00")
		@Nullable @JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DECIMAL, message = "{validation.amount.format}") String sellAmount,
		@Schema(description = "Exact amount to receive, a decimal string. Give this or sellAmount.")
		@Nullable @JsonDeserialize(using = StrictStringDeserializer.class)
		@Pattern(regexp = DECIMAL, message = "{validation.amount.format}") String buyAmount) {

	public static final String DECIMAL = "-?\\d{1,40}(\\.\\d{1,40})?";

}
