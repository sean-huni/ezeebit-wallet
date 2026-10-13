package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class ConversionSteps {

	private final Http http;
	private final ScenarioState state;

	public ConversionSteps(final Http http, final ScenarioState state) {
		this.http = http;
		this.state = state;
	}

	@When("{string} accepts quote {string} with key {string}")
	public void accepts(final String merchant, final String quote, final String key) {
		state.record(accept(merchant, quote, key));
	}

	@Given("{string} has accepted quote {string} with key {string}")
	public void hasAccepted(final String merchant, final String quote, final String key) {
		accepts(merchant, quote, key);
		theConversionIs("COMPLETED");
	}

	@Given("{string} has accepted quote {string} with key {string} and got PENDING")
	public void hasAcceptedPending(final String merchant, final String quote, final String key) {
		accepts(merchant, quote, key);
		theConversionIs("PENDING");
	}

	@When("{string} accepts quote {string} without an idempotency key")
	public void withoutKey(final String merchant, final String quote) {
		state.record(accept(merchant, quote, null));
	}

	@When("{string} looks up a conversion that does not exist")
	public void unknown(final String merchant) {
		state.record(http.conversion("GET", "/v1/merchants/%s/conversions/%s".formatted(state.merchant(merchant),
				UUID.randomUUID()), null, null));
	}

	@Then("the conversion is {word}")
	public void theConversionIs(final String status) {
		Reply reply = state.last();
		assertThat(reply.status()).as(reply.body()).isEqualTo("COMPLETED".equals(status) ? 201 : 202);
		assertThat(reply.<String>read("$.data.status")).isEqualTo(status);
	}

	@Then("the conversion shows status {word}")
	public void shows(final String status) {
		shows(state.conversion(), status, null);
	}

	@Then("the conversion shows status {word} because {word}")
	public void showsBecause(final String status, final String reason) {
		shows(state.conversion(), status, reason);
	}

	@Then("every one of those conversions shows status {word} because {word}")
	public void allShow(final String status, final String reason) {
		assertThat(state.conversions()).isNotEmpty().allSatisfy(id -> shows(id, status, reason));
	}

	@Then("both answers are identical")
	public void bothIdentical() {
		List<Reply> replies = state.replies();
		assertThat(replies.getLast().status()).isEqualTo(replies.get(replies.size() - 2).status());
		assertThat(replies.getLast().body()).isEqualTo(replies.get(replies.size() - 2).body());
	}

	@Then("the statement of {string} shows the conversion of quote {string} at exactly the quoted amounts")
	public void statementShowsQuotedAmounts(final String merchant, final String quote) {
		Reply statement = http.ledger("GET", "/v1/merchants/%s/entries".formatted(state.merchant(merchant)), null, null);
		List<Map<String, Object>> lines = statement.read("$.data.entries[?(@.type == 'CONVERSION')]");
		Reply offered = state.quote(quote);
		assertThat(lines).hasSize(2);
		assertThat(lines.get(0)).containsEntry("currency", offered.read("$.data.sellCurrency"));
		assertThat(new BigDecimal((String) lines.get(0).get("amount")))
				.isEqualByComparingTo(new BigDecimal(offered.<String>read("$.data.sellAmount")).negate());
		assertThat(lines.get(1)).containsEntry("currency", offered.read("$.data.buyCurrency"))
				.containsEntry("amount", offered.read("$.data.buyAmount"));
	}

	Reply accept(final String merchant, final String quote, final String key) {
		Reply reply = http.conversion("POST", "/v1/merchants/%s/conversions".formatted(state.merchant(merchant)), key,
				"{\"quoteId\":\"%s\"}".formatted(state.quoteId(quote)));
		remember(reply, state.merchant(merchant));
		return reply;
	}

	private void remember(final Reply reply, final UUID merchantId) {
		String id = reply.status() < 300 ? reply.read("$.data.conversionId")
				: reply.body().contains("\"conversionId\"") ? reply.read("$.conversionId") : null;
		if (id != null) {
			state.converted(id, merchantId);
		}
	}

	private void shows(final String conversionId, final String status, final String reason) {
		Reply reply = http.conversion("GET", "/v1/merchants/%s/conversions/%s".formatted(state.merchantOf(conversionId),
				conversionId), null, null);
		assertThat(reply.status()).as(reply.body()).isEqualTo(200);
		assertThat(reply.<String>read("$.data.status")).isEqualTo(status);
		assertThat(reply.<String>read("$.data.reason")).isEqualTo(reason);
	}

}
