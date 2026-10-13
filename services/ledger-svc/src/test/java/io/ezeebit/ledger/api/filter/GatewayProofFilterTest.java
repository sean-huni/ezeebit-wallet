package io.ezeebit.ledger.api.filter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GatewayProofFilterTest {

	// A blank secret would match a blank header, so the service refuses to start rather than run open.
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t" })
	void aBlankSecretFailsConstruction(final String secret) {
		assertThatThrownBy(() -> new GatewayProofFilter(secret, (request, response, handler, ex) -> null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("ezeebit.gateway.shared-secret");
	}

}
