package io.ezeebit.conversion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConversionApplicationTests {

	@Test
	void contextLoads() {
	}

}
