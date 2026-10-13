package io.ezeebit.ledger.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LogTextTest {

	@Test
	void replacesLineBreaksSoOneEventCannotForgeAnother() {
		assertThat(LogText.oneLine("refused\r\nINFO forged entry")).isEqualTo("refused__INFO forged entry");
	}

	@Test
	void replacesOtherControlCharacters() {
		assertThat(LogText.oneLine("a\tb\u0000c\u001bd")).isEqualTo("a_b_c_d");
	}

	@Test
	void keepsOrdinaryText() {
		assertThat(LogText.oneLine("USD/ZAR rate 18.25 é")).isEqualTo("USD/ZAR rate 18.25 é");
	}

	@Test
	void rendersObjectsAndNull() {
		assertThat(LogText.oneLine(Map.of("k", "v\n"))).isEqualTo("{k=v_}");
		assertThat(LogText.oneLine(null)).isEqualTo("null");
	}

	// NEL U+0085 and the Unicode separators end a line in some log viewers; U+009B is the C1 escape introducer.
	@Test
	void replacesC1ControlsAndUnicodeLineSeparators() {
		assertThat(LogText.oneLine("a\u0085b\u2028c\u2029d\u009Be")).isEqualTo("a_b_c_d_e");
	}

	@Test
	void sanitisedThrowableKeepsTypeAndStackButNotRawText() {
		IllegalStateException cause = new IllegalStateException("cause\nINFO forged");
		IllegalArgumentException ex = new IllegalArgumentException("top\r\nINFO forged", cause);

		Throwable logged = LogText.sanitised(ex);

		assertThat(logged.toString()).isEqualTo("java.lang.IllegalArgumentException: top__INFO forged");
		assertThat(logged.getStackTrace()).isEqualTo(ex.getStackTrace());
		assertThat(logged.getCause().toString()).isEqualTo("java.lang.IllegalStateException: cause_INFO forged");
		assertThat(logged.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());
	}

	@Test
	void sanitisedThrowableWithoutMessageNamesOnlyItsType() {
		assertThat(LogText.sanitised(new NullPointerException()).toString()).isEqualTo("java.lang.NullPointerException");
	}

}
