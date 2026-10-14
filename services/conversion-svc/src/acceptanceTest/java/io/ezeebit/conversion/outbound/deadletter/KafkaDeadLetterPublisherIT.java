package io.ezeebit.conversion.outbound.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import io.ezeebit.conversion.TestcontainersConfiguration;
import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.service.port.DeadLetter;
import io.ezeebit.conversion.service.port.DeadLetters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// The broker-down cases run last: they stop the one broker this class shares.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "conversion.sweep-interval=1h")
@ActiveProfiles("acceptance")
@TestMethodOrder(OrderAnnotation.class)
class KafkaDeadLetterPublisherIT {

	private static final String TOPIC = "ezeebit.conversion.ledger.deadletter";
	private static final KafkaContainer KAFKA = started();
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration CALLER_BUDGET = Duration.ofMillis(500);

	@Autowired
	private DeadLetters deadLetters;

	@Autowired
	private MeterRegistry registry;

	@Autowired
	private ProducerFactory<String, String> producers;

	@DynamicPropertySource
	static void broker(final DynamicPropertyRegistry properties) {
		properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	@BeforeAll
	static void topic() throws Exception {
		try (AdminClient admin = AdminClient
				.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
		}
	}

	@Test
	@Order(1)
	void aRecordedLetterLandsOnTheTopicKeyedByConversionWithEveryField() throws Exception {
		DeadLetter letter = letter();

		deadLetters.record(letter);

		List<ConsumerRecord<String, String>> mine = consumed(letter.conversionId().toString());
		assertThat(mine).hasSize(1);
		Map<String, Object> fields = JSON.readValue(mine.getFirst().value(), new TypeReference<>() {
		});
		assertThat(fields).containsExactlyInAnyOrderEntriesOf(expectedFields(letter));
	}

	@Test
	@Order(2)
	void aRecordedLetterIsCountedByOpAndReason() {
		DoubleSupplier count = counter(ConversionMetrics.DEAD_LETTERS, "op", "POST", "reason", "RETRIES_EXHAUSTED");
		double before = count.getAsDouble();

		deadLetters.record(letter());

		assertThat(count.getAsDouble() - before).isEqualTo(1.0);
	}

	@Test
	@Order(3)
	void aBrokerThatDiesAfterUseNeitherBlocksNorThrowsAndTheLossIsCounted() throws InterruptedException {
		KAFKA.stop();

		assertLostQuickly();
	}

	@Test
	@Order(4)
	void aProducerThatHasNeverReachedTheBrokerNeitherBlocksNorThrowsAndTheLossIsCounted() throws InterruptedException {
		KAFKA.stop();
		producers.reset();

		assertLostQuickly();
	}

	private void assertLostQuickly() throws InterruptedException {
		DoubleSupplier lost = counter(ConversionMetrics.DEAD_LETTER_FAILURES, "op", "POST");
		double before = lost.getAsDouble();

		long start = System.nanoTime();
		deadLetters.record(letter());
		Duration took = Duration.ofNanos(System.nanoTime() - start);

		assertThat(took).isLessThan(CALLER_BUDGET);
		assertThat(eventually(() -> lost.getAsDouble() - before >= 1.0, Duration.ofSeconds(5))).isTrue();
		assertThat(lost.getAsDouble() - before).isEqualTo(1.0);
	}

	private DoubleSupplier counter(final String name, final String... tags) {
		return () -> {
			Counter counter = registry.find(name).tags(tags).counter();
			return counter == null ? 0.0 : counter.count();
		};
	}

	private static boolean eventually(final BooleanSupplier condition, final Duration within)
			throws InterruptedException {
		long deadline = System.nanoTime() + within.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(50);
		}
		return condition.getAsBoolean();
	}

	private static List<ConsumerRecord<String, String>> consumed(final String key) {
		Properties config = new Properties();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		List<ConsumerRecord<String, String>> mine = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
				new StringDeserializer())) {
			consumer.subscribe(List.of(TOPIC));
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (mine.isEmpty() && System.nanoTime() < deadline) {
				consumer.poll(Duration.ofMillis(250)).forEach(record -> keep(record, key, mine));
			}
			// One more poll, so a duplicate sent just behind the first is seen too.
			consumer.poll(Duration.ofSeconds(1)).forEach(record -> keep(record, key, mine));
		}
		return mine;
	}

	private static void keep(final ConsumerRecord<String, String> record, final String key,
			final List<ConsumerRecord<String, String>> mine) {
		if (key.equals(record.key())) {
			mine.add(record);
		}
	}

	private static Map<String, Object> expectedFields(final DeadLetter letter) {
		return Map.of("op", letter.op(), "conversionId", letter.conversionId().toString(), "merchantId",
				letter.merchantId().toString(), "request", letter.request(), "attempts", letter.attempts(), "lastStatus",
				letter.lastStatus(), "lastReason", letter.lastReason(), "reason", letter.reason(), "at",
				letter.at().toString(), "traceId", letter.traceId());
	}

	private static DeadLetter letter() {
		return new DeadLetter("POST", UUID.randomUUID(), UUID.randomUUID(), "{\"sell\":\"ZAR\",\"amount\":\"100.00\"}", 3,
				"UNAVAILABLE", "LEDGER_DOWN", "RETRIES_EXHAUSTED", Instant.now().truncatedTo(ChronoUnit.MILLIS),
				"4bf92f3577b34da6a3ce929d0e0e4736");
	}

	private static KafkaContainer started() {
		// The pin carries a digest, which Testcontainers cannot match to apache/kafka by name alone.
		KafkaContainer kafka = new KafkaContainer(
				DockerImageName.parse(System.getProperty("kafka.image")).asCompatibleSubstituteFor("apache/kafka"));
		kafka.start();
		return kafka;
	}

}
