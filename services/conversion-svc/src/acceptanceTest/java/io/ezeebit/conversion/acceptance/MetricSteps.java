package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.ezeebit.conversion.common.ConversionMetrics;
import io.ezeebit.conversion.service.PendingOverdueGauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;

// The registry and the database outlive a scenario, so each assertion is a difference from the scenario's start.
public class MetricSteps {

	private static final List<String> COUNTERS = List.of(ConversionMetrics.QUOTES, ConversionMetrics.OUTCOMES,
			ConversionMetrics.SWEEPS);

	private final MeterRegistry registry;
	private final JdbcClient conversionDb;
	private final Map<Meter.Id, Double> baseline = new HashMap<>();
	private double overdueAtStart;

	public MetricSteps(final MeterRegistry registry, final JdbcClient conversionDb) {
		this.registry = registry;
		this.conversionDb = conversionDb;
	}

	@Before
	public void snapshot() {
		COUNTERS.forEach(name -> registry.find(name).counters()
				.forEach(counter -> baseline.put(counter.getId(), counter.count())));
		registry.find(ConversionMetrics.RATE_CALLS).timers()
				.forEach(timer -> baseline.put(timer.getId(), (double) timer.count()));
		overdueAtStart = overdue();
	}

	// The sweeper takes every due row in the shared database; earlier scenarios can leave PENDING rows that fall
	// due in real time. Moving them an hour out leaves this scenario's own conversion as the only one it can sweep.
	@Given("no earlier conversion can fall due for the sweeper")
	public void isolateFromEarlierConversions() {
		conversionDb.sql("UPDATE conversion SET deadline = now() + interval '1 hour' WHERE status = 'PENDING'").update();
		overdueAtStart = overdue();
	}

	@Then("conversion-svc counted these quotes:")
	public void countedQuotes(final DataTable expected) {
		assertCounted(ConversionMetrics.QUOTES, expected);
	}

	@Then("conversion-svc counted these conversions:")
	public void countedConversions(final DataTable expected) {
		assertCounted(ConversionMetrics.OUTCOMES, expected);
	}

	@Then("conversion-svc counted these sweeps:")
	public void countedSweeps(final DataTable expected) {
		assertCounted(ConversionMetrics.SWEEPS, expected);
	}

	@Then("conversion-svc timed {int} rate-service call(s) that answered")
	public void timedAnswered(final int calls) {
		assertTimed("answered", calls);
	}

	@Then("conversion-svc timed {int} rate-service call(s) that was/were unavailable")
	public void timedUnavailable(final int calls) {
		assertTimed("unavailable", calls);
	}

	@Then("conversion-svc reports {int} more overdue PENDING conversion(s) than at the start")
	public void overdueRose(final int more) {
		assertThat(overdue() - overdueAtStart).isEqualTo(more);
	}

	private double overdue() {
		return registry.get(PendingOverdueGauge.NAME).gauge().value();
	}

	private void assertTimed(final String outcome, final int calls) {
		Timer timer = registry.find(ConversionMetrics.RATE_CALLS).tag("outcome", outcome).timer();
		assertThat(timer).as(outcome).isNotNull();
		assertThat(timer.count() - baseline.getOrDefault(timer.getId(), 0.0)).as(outcome).isEqualTo(calls);
	}

	private void assertCounted(final String name, final DataTable expected) {
		for (Map<String, String> row : expected.asMaps()) {
			Map<String, String> tags = new HashMap<>(row);
			double want = Double.parseDouble(tags.remove("count"));
			Counter counter = registry.find(name).tags(tags.entrySet().stream()
					.flatMap(tag -> Stream.of(tag.getKey(), tag.getValue())).toArray(String[]::new)).counter();
			assertThat(counter).as("%s %s", name, tags).isNotNull();
			assertThat(counter.count() - baseline.getOrDefault(counter.getId(), 0.0)).as("%s %s", name, tags)
					.isEqualTo(want);
		}
	}

}
