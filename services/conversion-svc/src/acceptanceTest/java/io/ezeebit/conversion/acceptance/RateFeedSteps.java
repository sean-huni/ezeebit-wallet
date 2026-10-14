package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

// Each fault takes priority over the healthy feed for the rest of the scenario.
public class RateFeedSteps {

	private static final int FAULT = 1;
	// Longer than the 2 s read timeout served for the rate client.
	private static final int TOO_SLOW_MILLIS = 3_500;
	private static final String ANSWERED = "answered";
	private static final Duration JOURNAL_WAIT = Duration.ofSeconds(6);
	private static final Duration POLL = Duration.ofMillis(50);
	private static final Duration QUIET = Duration.ofMillis(500);

	// Counted by receipt time: WireMock journals a request only once its response is sent, so an abandoned
	// delayed request from an earlier scenario can enter the journal after this scenario's reset.
	private Instant countFrom;

	@Before
	public void countFromScenarioStart() {
		countFrom = armed();
	}

	@Given("the rate service answers too slowly")
	public void tooSlow() {
		countFrom = armed();
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT)
				.willReturn(RateFeed.rate("0.054254", "{{now}}").withFixedDelay(TOO_SLOW_MILLIS)));
	}

	@Given("the rate service fails with status {int}")
	public void fails(final int status) {
		countFrom = armed();
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT).willReturn(aResponse().withStatus(status)));
	}

	@Given("the rate service quotes {word} in {word} at {string} now")
	public void quotesNow(final String base, final String quote, final String rate) {
		Externals.get().rates().stubFor(RateFeed.pair(base, quote).atPriority(FAULT)
				.willReturn(RateFeed.rate(rate, "{{now}}")));
	}

	@Given("the rate service quotes {word} in {word} at {string} {int} seconds ago")
	public void quotesEarlier(final String base, final String quote, final String rate, final int seconds) {
		Externals.get().rates().stubFor(RateFeed.pair(base, quote).atPriority(FAULT)
				.willReturn(RateFeed.rate(rate, "{{now offset='-%d seconds'}}".formatted(seconds))));
	}

	@Given("the rate service answers with something that is not a rate")
	public void notARate() {
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("<html>maintenance</html>")));
	}

	@Given("the rate service answers its first request after {int} seconds and then normally")
	public void lateOnce(final int seconds) {
		countFrom = armed();
		Externals.get().rates().stubFor(RateFeed.any().atPriority(FAULT).inScenario(UUID.randomUUID().toString())
				.whenScenarioStateIs(STARTED).willSetStateTo(ANSWERED)
				.willReturn(RateFeed.rate("0.054254", "{{now}}").withFixedDelay(seconds * 1000)));
	}

	// A delayed answer the client abandoned is journaled seconds after the refusal: wait for the expected count,
	// then for a quiet spell in which no extra arrives. Counts requests received since the fault was armed.
	@Then("the rate service was asked {int} time(s)")
	public void asked(final int times) throws InterruptedException {
		long deadline = System.nanoTime() + JOURNAL_WAIT.toNanos();
		while (callsSince(countFrom) < times && System.nanoTime() < deadline) {
			Thread.sleep(POLL.toMillis());
		}
		Thread.sleep(QUIET.toMillis());
		assertThat(callsSince(countFrom)).isEqualTo(times);
	}

	// WireMock stamps receipt in whole milliseconds; a finer mark could exclude a request from the same millisecond.
	private static Instant armed() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}

	private static long callsSince(final Instant from) {
		return Externals.get().rates().findAll(getRequestedFor(urlPathEqualTo("/rates"))).stream()
				.filter(call -> !call.getLoggedDate().toInstant().isBefore(from)).count();
	}

}
