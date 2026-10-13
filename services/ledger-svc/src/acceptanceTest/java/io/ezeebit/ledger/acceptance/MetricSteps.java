package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import io.ezeebit.ledger.common.LedgerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

// The registry outlives a scenario, so each assertion is a difference from the scenario's own start.
public class MetricSteps {

	private final MeterRegistry registry;
	private final Map<Meter.Id, Double> baseline = new HashMap<>();

	public MetricSteps(final MeterRegistry registry) {
		this.registry = registry;
	}

	@Before
	public void snapshot() {
		registry.find(LedgerMetrics.DEPOSITS).counters().forEach(counter -> baseline.put(counter.getId(), counter.count()));
		registry.find(LedgerMetrics.CONVERSION_POSTINGS).counters()
				.forEach(counter -> baseline.put(counter.getId(), counter.count()));
	}

	@Then("the ledger counted these deposits:")
	public void countedDeposits(final DataTable expected) {
		assertCounted(LedgerMetrics.DEPOSITS, expected);
	}

	@Then("the ledger counted these conversion postings:")
	public void countedPostings(final DataTable expected) {
		assertCounted(LedgerMetrics.CONVERSION_POSTINGS, expected);
	}

	private void assertCounted(final String name, final DataTable expected) {
		for (Map<String, String> row : expected.asMaps()) {
			Map<String, String> tags = new HashMap<>(row);
			double want = Double.parseDouble(tags.remove("count"));
			Counter counter = registry.find(name).tags(tags.entrySet().stream()
					.flatMap(tag -> Stream.of(tag.getKey(), tag.getValue())).toArray(String[]::new))
					.counter();
			assertThat(counter).as("%s %s", name, tags).isNotNull();
			assertThat(counter.count() - baseline.getOrDefault(counter.getId(), 0.0)).as("%s %s", name, tags)
					.isEqualTo(want);
		}
	}

}
