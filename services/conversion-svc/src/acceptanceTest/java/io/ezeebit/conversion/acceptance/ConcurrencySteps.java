package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class ConcurrencySteps {

	private final Http http;
	private final ScenarioState state;

	public ConcurrencySteps(final Http http, final ScenarioState state) {
		this.http = http;
		this.state = state;
	}

	@Given("{string} has {int} quotes each to sell {string} {word}, alternately for {word} and {word}")
	public void quotes(final String merchant, final int count, final String amount, final String sell, final String first,
			final String second) {
		UUID id = state.merchant(merchant);
		for (int i = 0; i < count; i++) {
			Reply quote = http.conversion("POST", "/api/v1/merchants/%s/quotes".formatted(id), null, """
					{"sell":"%s","buy":"%s","sellAmount":"%s"}""".formatted(sell, i % 2 == 0 ? first : second, amount));
			assertThat(quote.status()).as(quote.body()).isEqualTo(201);
			state.quoted("batch-%02d".formatted(i), quote);
		}
	}

	@When("{string} accepts all its quotes at the same time, each with its own key")
	public void acceptAll(final String merchant) {
		UUID id = state.merchant(merchant);
		List<String> quoteIds = state.quoteNames().stream().map(state::quoteId).toList();
		state.recordAll(AtOnce.run(quoteIds.size(), i -> accept(id, quoteIds.get(i), "key-%d".formatted(i))));
	}

	@When("{string} accepts quote {string} {int} times at the same time with key {string}")
	public void sameRequestAtOnce(final String merchant, final String quote, final int count, final String key) {
		UUID id = state.merchant(merchant);
		String quoteId = state.quoteId(quote);
		state.recordAll(AtOnce.run(count, i -> accept(id, quoteId, key)));
	}

	@When("{string} accepts quote {string} at the same time with keys {string} and {string}")
	public void twoKeysAtOnce(final String merchant, final String quote, final String first, final String second) {
		UUID id = state.merchant(merchant);
		String quoteId = state.quoteId(quote);
		List<String> keys = List.of(first, second);
		state.recordAll(AtOnce.run(2, i -> accept(id, quoteId, keys.get(i))));
	}

	@Then("exactly {int} conversions complete and the rest are refused with {int} {word}")
	public void completeAndRefused(final int completed, final int status, final String code) {
		assertThat(state.replies().stream().filter(reply -> reply.status() == 201)).hasSize(completed);
		assertThat(state.replies().stream().filter(reply -> reply.status() != 201)).allSatisfy(reply -> {
			assertThat(reply.status()).as(reply.body()).isEqualTo(status);
			assertThat(reply.<String>read("$.code")).isEqualTo(code);
		});
	}

	@Then("every answer is COMPLETED and identical")
	public void allCompletedAndIdentical() {
		assertThat(state.replies()).extracting(Reply::status).containsOnly(201);
		assertThat(state.replies()).extracting(Reply::body).containsOnly(state.replies().getFirst().body());
	}

	@Then("one answer is COMPLETED and the other is refused with {int} {word}")
	public void oneCompletedOneRefused(final int status, final String code) {
		assertThat(state.replies()).extracting(Reply::status).containsExactlyInAnyOrder(201, status);
		Reply refused = state.replies().stream().filter(reply -> reply.status() == status).findFirst().orElseThrow();
		assertThat(refused.<String>read("$.code")).isEqualTo(code);
	}

	private Reply accept(final UUID merchantId, final String quoteId, final String key) {
		return http.conversion("POST", "/api/v1/merchants/%s/conversions".formatted(merchantId), key,
				"{\"quoteId\":\"%s\"}".formatted(quoteId));
	}

}
