package io.ezeebit.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

	private final RequestFingerprint fingerprint = new RequestFingerprint();

	@Test
	void identicalPartsGiveTheSameFingerprint() {
		assertThat(fingerprint.of("m", "ZAR", "100.00", "ref"))
				.isEqualTo(fingerprint.of("m", "ZAR", "100.00", "ref"));
	}

	@Test
	void fingerprintIsSha256Hex() {
		assertThat(fingerprint.of("m")).matches("[0-9a-f]{64}");
	}

	@Test
	void amountIsHashedAsWrittenSoTrailingZerosCount() {
		assertThat(fingerprint.of("m", "ZAR", "100.0", "ref"))
				.isNotEqualTo(fingerprint.of("m", "ZAR", "100.00", "ref"));
	}

	@Test
	void movingASeparatorBetweenPartsChangesTheFingerprint() {
		assertThat(fingerprint.of("ab", "c")).isNotEqualTo(fingerprint.of("a", "bc"));
	}

	@Test
	void aPartContainingAControlCharacterCannotImitateTwoParts() {
		assertThat(fingerprint.of("a\u001fb", "c")).isNotEqualTo(fingerprint.of("a", "b\u001fc"));
	}

	@Test
	void anyChangedPartChangesTheFingerprint() {
		assertThat(fingerprint.of("m", "ZAR", "100.00", "ref"))
				.isNotEqualTo(fingerprint.of("m", "ZAR", "100.00", "ref2"));
	}

}
