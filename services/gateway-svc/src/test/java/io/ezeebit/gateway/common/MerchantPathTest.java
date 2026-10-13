package io.ezeebit.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.ezeebit.gateway.common.MerchantPath.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MerchantPathTest {

	private static final String ID = "7f9c2a1e-3b4d-4c5e-8f6a-0b1c2d3e4f5a";

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/merchants/%s/balances", "/api/v1/merchants/%s", "/api/v1/merchants/%s/conversions/abc-1",
		"/api/v1/merchants/%s/entries/" })
	void aMerchantRouteIsKeyedOnItsCanonicalId(final String template) {
		assertThat(MerchantPath.of(template.formatted(ID))).isEqualTo(new MerchantPath(Kind.MERCHANT, "merchant:%s".formatted(ID)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/merchants/7F9C2A1E-3B4D-4C5E-8F6A-0B1C2D3E4F5A/balances" })
	void caseVariantsOfOneIdShareOneBucket(final String path) {
		assertThat(MerchantPath.of(path).key()).isEqualTo("merchant:%s".formatted(ID));
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/merchants/m1/balances", "/api/v1/merchants//balances", "/api/v1/merchants/",
		"/api/v1/merchants/1-1-1-1-1/balances", "/api/v1/merchants/7f9c2a1e3b4d4c5e8f6a0b1c2d3e4f5a/balances",
		"/api/v1/merchants/7f9c2a1e-3b4d-4c5e-8f6a-0b1c2d3e4f5a0/balances" })
	void anIdThatIsNotAStrictUuidIsRefused(final String path) {
		assertThat(MerchantPath.of(path).kind()).isIn(Kind.INVALID_ID, Kind.MALFORMED);
		assertThat(MerchantPath.of(path).key()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/merchants/m1/balances", "/api/v1/merchants/", "/api/v1/merchants/1-1-1-1-1/balances" })
	void aNonUuidIdIsAnInvalidId(final String path) {
		assertThat(MerchantPath.of(path).kind()).isEqualTo(Kind.INVALID_ID);
	}

	// Each would route (the matcher drops ;params and decodes %xx) while naming a different bucket, or none.
	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/merchants;x=1/%s/balances", "/api/v1/merchants/%s;x=1/balances",
		"/api/v1/merchants/%%37f9c2a1e-3b4d-4c5e-8f6a-0b1c2d3e4f5a/balances", "/api/v1/merchants/%s/../other/balances",
		"/api/v1//merchants/%s/balances", "/api/v1/merchants/%s/./balances", "/api/v1/%%6derchants/%s/balances",
		"/%%61pi/v1/merchants/%s/balances", "/ap%%69/v1/merchants/%s/balances", "/api;x/v1/merchants/%s/balances",
		"/api/v1/merchants/%%2e%%2e/%s/balances", "//api/v1/merchants/%s/balances", "/x/../api/v1/merchants/%s/balances",
		"/actuator/health;x=%s", "/actuator/%%68ealth/%s" })
	void aPathNotInCanonicalFormIsMalformed(final String template) {
		assertThat(MerchantPath.of(template.formatted(ID)).kind()).isEqualTo(Kind.MALFORMED);
	}

	@ParameterizedTest
	@CsvSource({ "/actuator/health", "/internal/v1/deposits", "/api/v2/merchants/x/balances", "/", "/apiv1/merchants" })
	void anythingElseIsNotAMerchantRoute(final String path) {
		assertThat(MerchantPath.of(path).kind()).isEqualTo(Kind.NOT_MERCHANT);
	}

}
