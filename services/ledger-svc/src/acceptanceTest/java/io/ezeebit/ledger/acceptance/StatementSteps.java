package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class StatementSteps {

	private final LedgerClient ledger;
	private final ScenarioState state;
	private final List<Map<String, Object>> pagedLines = new ArrayList<>();

	public StatementSteps(final LedgerClient ledger, final ScenarioState state) {
		this.ledger = ledger;
		this.state = state;
	}

	@When("{string} reads its {word} statement")
	public void readsStatement(final String merchant, final String currency) {
		state.record(ledger.get("%s?currency=%s".formatted(path(merchant), currency)));
	}

	@When("{string} reads its statement in every currency")
	public void readsFullStatement(final String merchant) {
		state.record(ledger.get(path(merchant)));
	}

	@When("{string} reads its {word} statement up to {string}")
	public void readsStatementUpTo(final String merchant, final String currency, final String to) {
		state.record(ledger.get("%s?currency=%s&to=%s".formatted(path(merchant), currency, encode(to))));
	}

	@When("{string} reads its {word} statement from {string}")
	public void readsStatementFrom(final String merchant, final String currency, final String from) {
		state.record(ledger.get("%s?currency=%s&from=%s".formatted(path(merchant), currency, encode(from))));
	}

	@When("{string} reads its {word} statement from {string} up to {string}")
	public void readsStatementBetween(final String merchant, final String currency, final String from, final String to) {
		state.record(ledger.get("%s?currency=%s&from=%s&to=%s".formatted(path(merchant), currency, encode(from),
				encode(to))));
	}

	@When("{string} reads its {word} statement from the time of deposit {string} up to the time of deposit {string}")
	public void readsBetweenDeposits(final String merchant, final String currency, final String fromKey,
			final String toKey) {
		readsStatementBetween(merchant, currency, state.answerTo(fromKey).read("$.data.created"),
				state.answerTo(toKey).read("$.data.created"));
	}

	@When("{string} reads its {word} statement {int} entries at a time")
	public void readsInPages(final String merchant, final String currency, final int limit) {
		String cursor = "0";
		while (cursor != null) {
			Reply page = ledger.get("%s?currency=%s&limit=%d&after=%s".formatted(path(merchant), currency, limit, cursor));
			assertThat(page.status()).as(page.body()).isEqualTo(200);
			pagedLines.addAll(page.read("$.data.entries"));
			cursor = page.read("$.data.nextCursor");
		}
	}

	@Then("the statement shows:")
	public void theStatementShows(final DataTable expected) {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(200);
		assertThat(project(state.last().read("$.data.entries"))).isEqualTo(expected.asMaps());
	}

	@Then("the pages together show:")
	public void thePagesTogetherShow(final DataTable expected) {
		assertThat(project(pagedLines)).isEqualTo(expected.asMaps());
	}

	@Then("the statement is empty")
	public void theStatementIsEmpty() {
		assertThat(state.last().status()).as(state.last().body()).isEqualTo(200);
		assertThat(state.last().<List<?>>read("$.data.entries")).isEmpty();
	}

	@Then("the {word} balance of {string} equals the sum of its statement")
	public void balanceEqualsSumOfStatement(final String currency, final String merchant) {
		readsInPages(merchant, currency, 500);
		BigDecimal sum = pagedLines.stream().map(line -> new BigDecimal((String) line.get("amount")))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		Reply balances = ledger.get("/api/v1/merchants/%s/balances".formatted(state.merchant(merchant)));
		List<String> held = balances.read("$.data[?(@.currency == '%s')].balance".formatted(currency));
		assertThat(new BigDecimal(held.getFirst())).isEqualByComparingTo(sum);
	}

	private static String encode(final String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String path(final String merchant) {
		return "/api/v1/merchants/%s/entries".formatted(state.merchant(merchant));
	}

	private static List<Map<String, String>> project(final List<Map<String, Object>> lines) {
		return lines.stream().map(line -> Map.of(
				"currency", (String) line.get("currency"),
				"amount", (String) line.get("amount"),
				"balance after", (String) line.get("balanceAfter"),
				"reference", (String) line.get("reference"))).toList();
	}

}
