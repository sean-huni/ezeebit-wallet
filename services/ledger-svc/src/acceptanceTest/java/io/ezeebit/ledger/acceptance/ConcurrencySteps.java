package io.ezeebit.ledger.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class ConcurrencySteps {

	private final LedgerClient ledger;
	private final ScenarioState state;

	public ConcurrencySteps(final LedgerClient ledger, final ScenarioState state) {
		this.ledger = ledger;
		this.state = state;
	}

	@When("the rail sends {int} deposits of {string} {word} for {string} at the same time, each with its own key")
	public void distinctAtOnce(final int count, final String amount, final String currency, final String merchant) {
		UUID merchantId = state.merchant(merchant);
		state.recordAll(atOnce(count, i -> {
			String key = "burst-%d".formatted(i);
			return ledger.deposit(key, DepositBody.of(merchantId, currency, amount, key));
		}));
	}

	@When("the rail sends the same deposit of {string} {word} for {string} {int} times at the same time with key {string}")
	public void sameAtOnce(final String amount, final String currency, final String merchant, final int count,
			final String key) {
		String body = DepositBody.of(state.merchant(merchant), currency, amount, "transfer %s".formatted(key));
		state.recordAll(atOnce(count, i -> ledger.deposit(key, body)));
	}

	@Then("every answer is accepted")
	public void everyAnswerIsAccepted() {
		assertThat(state.replies()).extracting(Reply::status).containsOnly(201);
	}

	@Then("every answer is identical")
	public void everyAnswerIsIdentical() {
		assertThat(state.replies()).extracting(Reply::body).containsOnly(state.replies().getFirst().body());
	}

	// Scenario-scoped state is bound to the step thread, so workers only see values resolved before.
	// All requests wait on one gate and are released together, so they genuinely overlap.
	private static List<Reply> atOnce(final int count, final IntFunction<Reply> request) {
		CountDownLatch gate = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
			List<Future<Reply>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					gate.await();
					return request.apply(index);
				}));
			}
			gate.countDown();
			List<Reply> replies = new ArrayList<>();
			for (Future<Reply> future : futures) {
				replies.add(future.get());
			}
			return replies;
		}
		catch (Exception ex) {
			throw new IllegalStateException("concurrent requests did not complete", ex);
		}
	}

}
