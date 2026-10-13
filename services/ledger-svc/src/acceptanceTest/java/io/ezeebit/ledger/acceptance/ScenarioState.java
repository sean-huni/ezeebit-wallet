package io.ezeebit.ledger.acceptance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Per scenario: every merchant is a fresh id, so scenarios never see each other's money.
public class ScenarioState {

	private final Map<String, UUID> merchants = new HashMap<>();
	private final Map<String, String> sentBodies = new HashMap<>();
	private final Map<String, Reply> answers = new HashMap<>();
	private final List<Reply> replies = new ArrayList<>();

	public UUID merchant(final String name) {
		return merchants.computeIfAbsent(name, ignored -> UUID.randomUUID());
	}

	public void sent(final String key, final String body) {
		sentBodies.put(key, body);
	}

	public void answered(final String key, final Reply reply) {
		answers.put(key, reply);
	}

	public Reply answerTo(final String key) {
		return answers.get(key);
	}

	public String bodySentWith(final String key) {
		return sentBodies.get(key);
	}

	public void record(final Reply reply) {
		replies.add(reply);
	}

	public void recordAll(final List<Reply> batch) {
		replies.clear();
		replies.addAll(batch);
	}

	public Reply last() {
		return replies.getLast();
	}

	public List<Reply> replies() {
		return List.copyOf(replies);
	}

}
