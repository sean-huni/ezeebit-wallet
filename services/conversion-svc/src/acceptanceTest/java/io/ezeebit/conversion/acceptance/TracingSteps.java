package io.ezeebit.conversion.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

// The caller's span id is fixed; only the trace id is under test. Every outbound call must carry that trace id.
public class TracingSteps {

	private static final String W3C = "00-%s-00f067aa0ba902b7-01";
	private static final String TRACEPARENT = "traceparent";

	private final Http http;
	private final ScenarioState state;
	public TracingSteps(final Http http, final ScenarioState state) {
		this.http = http;
		this.state = state;
	}

	@When("{string} asks for quote {string} to sell {string} {word} for {word} inside trace {string}")
	public void quoteInTrace(final String merchant, final String name, final String amount, final String sell, final String buy,
			final String traceId) {
		Reply reply = http.conversion("POST", "/v1/merchants/%s/quotes".formatted(state.merchant(merchant)), null, """
				{"sell":"%s","buy":"%s","sellAmount":"%s"}""".formatted(sell, buy, amount), W3C.formatted(traceId));
		state.record(reply);
		state.quoted(name, reply);
	}

	@When("{string} accepts quote {string} with key {string} inside trace {string}")
	public void acceptInTrace(final String merchant, final String quote, final String key, final String traceId) {
		state.record(http.conversion("POST", "/v1/merchants/%s/conversions".formatted(state.merchant(merchant)), key,
				"{\"quoteId\":\"%s\"}".formatted(state.quoteId(quote)), W3C.formatted(traceId)));
	}

	@Then("the rate feed was called inside trace {string}")
	public void rateFeedInTrace(final String traceId) {
		assertThat(traceIds(Externals.get().rates().findAll(getRequestedFor(urlPathEqualTo("/rates"))))).contains(traceId);
	}

	@Then("the ledger was called inside trace {string}")
	public void ledgerInTrace(final String traceId) {
		assertThat(Externals.get().ledgerProxy().traceparents("Post").stream().map(header -> header.split("-")[1]))
				.contains(traceId);
	}

	@Then("every rate feed call carried a trace context")
	public void everyRateCallTraced() {
		List<LoggedRequest> calls = Externals.get().rates().findAll(getRequestedFor(urlPathEqualTo("/rates")));
		assertThat(calls).isNotEmpty()
				.allSatisfy(call -> assertThat(call.getHeader(TRACEPARENT)).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));
	}

	private static List<String> traceIds(final List<LoggedRequest> calls) {
		return calls.stream().map(call -> call.getHeader(TRACEPARENT)).filter(header -> header != null)
				.map(header -> header.split("-")[1]).toList();
	}

}
