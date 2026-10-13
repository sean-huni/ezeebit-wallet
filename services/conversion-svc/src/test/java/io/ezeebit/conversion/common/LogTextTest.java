package io.ezeebit.conversion.common;

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

}
