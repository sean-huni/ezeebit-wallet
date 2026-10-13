package io.ezeebit.conversion.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.jdbc.core.simple.JdbcClient;

public class QuoteSteps {

	private final Http http;
	private final ScenarioState state;
	private final JdbcClient conversionDb;

	public QuoteSteps(final Http http, final ScenarioState state, final JdbcClient conversionDb) {
		this.http = http;
		this.state = state;
		this.conversionDb = conversionDb;
	}

	@When("{string} asks for quote {string} to sell {string} {word} for {word}")
	public void quoteSelling(final String merchant, final String name, final String amount, final String sell,
			final String buy) {
		ask(merchant, name, """
				{"sell":"%s","buy":"%s","sellAmount":"%s"}""".formatted(sell, buy, amount));
	}

	@When("{string} asks for quote {string} to buy {string} {word} with {word}")
	public void quoteBuying(final String merchant, final String name, final String amount, final String buy,
			final String sell) {
		ask(merchant, name, """
				{"sell":"%s","buy":"%s","buyAmount":"%s"}""".formatted(sell, buy, amount));
	}

	@When("{string} asks for a quote to sell {word} for {word} naming both amounts")
	public void quoteNamingBoth(final String merchant, final String sell, final String buy) {
		ask(merchant, "both", """
				{"sell":"%s","buy":"%s","sellAmount":"10.00","buyAmount":"1.000000"}""".formatted(sell, buy));
	}

	@Given("{string} has quote {string} to sell {string} {word} for {word}")
	public void hasQuoteSelling(final String merchant, final String name, final String amount, final String sell,
			final String buy) {
		quoteSelling(merchant, name, amount, sell, buy);
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(201);
	}

	@Given("{string} has quote {string} to buy {string} {word} with {word}")
	public void hasQuoteBuying(final String merchant, final String name, final String amount, final String buy,
			final String sell) {
		quoteBuying(merchant, name, amount, buy, sell);
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(201);
	}

	@Given("{string} was quoted {string} {word} for {word} a moment ago")
	public void wasQuoted(final String merchant, final String amount, final String sell, final String buy) {
		hasQuoteSelling(merchant, "earlier", amount, sell, buy);
	}

	@Given("quote {string} has expired")
	public void hasExpired(final String name) {
		conversionDb.sql("UPDATE quote SET expires_at = now() - interval '1 second' WHERE id = :id")
				.param("id", java.util.UUID.fromString(state.quoteId(name))).update();
	}

	@Then("quote {string} is offered:")
	public void isOffered(final String name, final DataTable expected) {
		Reply quote = state.quote(name);
		assertThat(quote.status()).as(quote.body()).isEqualTo(201);
		Map<String, String> want = expected.asMaps().getFirst();
		want.forEach((field, value) -> assertThat(String.valueOf(quote.<Object>read("$.data." + field)))
				.as(field).isEqualTo(value));
	}

	@Then("quote {string} expires {int} seconds after it was made")
	public void expiresAfter(final String name, final int seconds) {
		Instant expires = Instant.parse(state.quote(name).read("$.data.expiresAt"));
		Instant created = conversionDb.sql("SELECT created FROM quote WHERE id = :id")
				.param("id", java.util.UUID.fromString(state.quoteId(name))).query(java.time.OffsetDateTime.class).single().toInstant();
		assertThat(Duration.between(created, expires)).isBetween(Duration.ofSeconds(seconds - 1),
				Duration.ofSeconds(seconds + 1));
	}

	@Then("the request is refused with {int} {word} because {word}")
	public void refusedBecause(final int status, final String code, final String reason) {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(status);
		assertThat(state.last().<String>read("$.code")).isEqualTo(code);
		assertThat(state.last().<String>read("$.reason")).isEqualTo(reason);
	}

	@Then("the refusal came within {int} seconds")
	public void within(final int seconds) {
		assertThat(state.last().millis()).isLessThan(seconds * 1000L);
	}

	@Then("no quote is stored for {string}")
	public void noQuote(final String merchant) {
		onlyQuotes(0, merchant);
	}

	@Then("only {int} quote(s) is/are stored for {string}")
	public void onlyQuotes(final int count, final String merchant) {
		assertThat(conversionDb.sql("SELECT count(*) FROM quote WHERE merchant_id = :merchant")
				.param("merchant", state.merchant(merchant)).query(Integer.class).single()).isEqualTo(count);
	}

	private void ask(final String merchant, final String name, final String body) {
		Reply reply = http.conversion("POST", "/api/v1/merchants/%s/quotes".formatted(state.merchant(merchant)), null, body);
		state.record(reply);
		state.quoted(name, reply);
	}

}
