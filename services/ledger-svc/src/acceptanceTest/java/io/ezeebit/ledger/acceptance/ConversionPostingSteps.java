package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ConversionPostingSteps {

	private static final String POSTINGS = "/internal/v1/conversion-postings";

	private final LedgerClient ledger;
	private final ScenarioState state;
	private final JdbcClient jdbc;

	public ConversionPostingSteps(final LedgerClient ledger, final ScenarioState state, final JdbcClient jdbc) {
		this.ledger = ledger;
		this.state = state;
		this.jdbc = jdbc;
	}

	@When("conversion {string} for {string} sells {string} {word} for {string} {word} with a deadline {int} minute(s) {word}")
	public void sells(final String conversion, final String merchant, final String sellAmount, final String sellCurrency,
			final String buyAmount, final String buyCurrency, final int minutes, final String direction) {
		Duration offset = Duration.ofMinutes("ago".equals(direction) ? -minutes : minutes);
		String body = body(state.conversion(conversion), state.merchant(merchant), sellCurrency, sellAmount, buyCurrency,
				buyAmount, Instant.now().plus(offset));
		state.sent(conversion, body);
		state.record(ledger.post(POSTINGS, body));
	}

	@Given("conversion {string} for {string} has sold {string} {word} for {string} {word}")
	public void hasSold(final String conversion, final String merchant, final String sellAmount, final String sellCurrency,
			final String buyAmount, final String buyCurrency) {
		sells(conversion, merchant, sellAmount, sellCurrency, buyAmount, buyCurrency, 1, "away");
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(201);
	}

	@When("conversion {string} is sent again")
	public void sentAgain(final String conversion) {
		state.record(ledger.post(POSTINGS, state.bodySentWith(conversion)));
	}

	@When("the sweeper voids conversion {string} for {string}")
	public void voids(final String conversion, final String merchant) {
		state.record(ledger.post("%s/%s/void".formatted(POSTINGS, state.conversion(conversion)),
				"{\"merchantId\":\"%s\"}".formatted(state.merchant(merchant))));
	}

	@When("conversion {string} is looked up without a merchant id")
	public void lookedUpWithoutMerchant(final String conversion) {
		state.record(ledger.get("%s/%s".formatted(POSTINGS, state.conversion(conversion))));
	}

	@When("{int} conversions for {string} each sell {string} {word} at the same time, buying in turn:")
	public void concurrent(final int count, final String merchant, final String sellAmount, final String sellCurrency,
			final DataTable buys) {
		UUID merchantId = state.merchant(merchant);
		Instant deadline = Instant.now().plus(Duration.ofMinutes(1));
		List<Map<String, String>> targets = buys.asMaps();
		List<String> bodies = IntStream.range(0, count).mapToObj(i -> targets.get(i % targets.size()))
				.map(buy -> body(UUID.randomUUID(), merchantId, sellCurrency, sellAmount, buy.get("currency"),
						buy.get("amount"), deadline)).toList();
		state.recordAll(ConcurrencySteps.atOnce(count, i -> ledger.post(POSTINGS, bodies.get(i))));
	}

	@Then("the posting is accepted with status {word}")
	public void accepted(final String status) {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(201);
		assertThat(state.last().<String>read("$.data.status")).isEqualTo(status);
	}

	@Then("looking up conversion {string} for {string} shows status {word}")
	public void lookupShows(final String conversion, final String merchant, final String status) {
		Reply reply = lookup(conversion, merchant);
		assertThat(reply.status()).as(reply.body()).isEqualTo(200);
		assertThat(reply.<String>read("$.data.status")).isEqualTo(status);
	}

	@Then("looking up conversion {string} for {string} is refused with {int} {word}")
	public void lookupRefused(final String conversion, final String merchant, final int status, final String code) {
		Reply reply = lookup(conversion, merchant);
		assertThat(reply.status()).as(reply.body()).isEqualTo(status);
		assertThat(reply.<String>read("$.code")).isEqualTo(code);
	}

	@Then("conversion {string} of {string} is recorded as {word} with no entries")
	public void recordedWithoutEntries(final String conversion, final String merchant, final String status) {
		List<String> statuses = jdbc.sql("""
				SELECT status FROM journal WHERE merchant_id = :merchant AND type = 'CONVERSION' AND idempotency_key = :key""")
				.param("merchant", state.merchant(merchant)).param("key", state.conversion(conversion).toString())
				.query(String.class).list();
		assertThat(statuses).containsExactly(status);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM entry e JOIN journal j ON j.id = e.journal_id
				WHERE j.merchant_id = :merchant AND j.type = 'CONVERSION' AND j.idempotency_key = :key""")
				.param("merchant", state.merchant(merchant)).param("key", state.conversion(conversion).toString())
				.query(Integer.class).single()).isZero();
	}

	@Then("looking up conversion {string} for {string} shows status {word} because {word}")
	public void lookupShowsBecause(final String conversion, final String merchant, final String status,
			final String reason) {
		Reply reply = lookup(conversion, merchant);
		assertThat(reply.status()).as(reply.body()).isEqualTo(200);
		assertThat(reply.<String>read("$.data.status")).isEqualTo(status);
		assertThat(reply.<String>read("$.data.reason")).isEqualTo(reason);
	}

	@Then("exactly {int} postings are accepted and the rest are refused with {int} {word}")
	public void acceptedAndRefused(final int accepted, final int status, final String code) {
		assertThat(state.replies().stream().filter(reply -> reply.status() == 201)).hasSize(accepted);
		assertThat(state.replies().stream().filter(reply -> reply.status() != 201))
				.allSatisfy(reply -> {
					assertThat(reply.status()).as(reply.body()).isEqualTo(status);
					assertThat(reply.<String>read("$.code")).isEqualTo(code);
				});
	}

	@Then("exactly {int} posting(s) is/are recorded for conversion {string} of {string}")
	public void recorded(final int count, final String conversion, final String merchant) {
		Integer journals = jdbc.sql("""
				SELECT count(*) FROM journal WHERE merchant_id = :merchant AND type = 'CONVERSION' AND idempotency_key = :key""")
				.param("merchant", state.merchant(merchant)).param("key", state.conversion(conversion).toString())
				.query(Integer.class).single();
		assertThat(journals).isEqualTo(count);
	}

	@Then("conversion {string} for {string} has the entries:")
	public void hasEntries(final String conversion, final String merchant, final DataTable expected) {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT a.type AS account, e.currency, e.amount FROM entry e
				JOIN account a ON a.id = e.account_id JOIN journal j ON j.id = e.journal_id
				WHERE j.merchant_id = :merchant AND j.type = 'CONVERSION' AND j.idempotency_key = :key ORDER BY e.seq""")
				.param("merchant", state.merchant(merchant)).param("key", state.conversion(conversion).toString())
				.query().listOfRows();
		assertThat(rows).hasSize(expected.height() - 1);
		for (int i = 0; i < rows.size(); i++) {
			Map<String, String> want = expected.asMaps().get(i);
			assertThat(rows.get(i).get("account")).isEqualTo(want.get("account"));
			assertThat(rows.get(i).get("currency")).isEqualTo(want.get("currency"));
			assertThat((BigDecimal) rows.get(i).get("amount")).isEqualByComparingTo(want.get("amount"));
		}
	}

	private Reply lookup(final String conversion, final String merchant) {
		return ledger.get("%s/%s?merchantId=%s".formatted(POSTINGS, state.conversion(conversion), state.merchant(merchant)));
	}

	private static String body(final UUID conversionId, final UUID merchantId, final String sellCurrency,
			final String sellAmount, final String buyCurrency, final String buyAmount, final Instant deadline) {
		return """
				{"conversionId":"%s","merchantId":"%s","sellCurrency":"%s","sellAmount":"%s","buyCurrency":"%s","buyAmount":"%s","deadline":"%s"}"""
				.formatted(conversionId, merchantId, sellCurrency, sellAmount, buyCurrency, buyAmount, deadline);
	}

}
