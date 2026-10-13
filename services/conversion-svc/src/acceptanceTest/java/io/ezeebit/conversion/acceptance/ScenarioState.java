package io.ezeebit.conversion.acceptance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Per scenario: every merchant is a fresh id, so scenarios never see each other's money.
public class ScenarioState {

	private final Map<String, UUID> merchants = new HashMap<>();
	private final Map<String, Reply> quotes = new HashMap<>();
	private final List<Reply> replies = new ArrayList<>();
	private final List<String> conversions = new ArrayList<>();
	private final Map<String, UUID> conversionMerchants = new HashMap<>();

	public UUID merchant(final String name) {
		return merchants.computeIfAbsent(name, ignored -> UUID.randomUUID());
	}

	public void quoted(final String name, final Reply reply) {
		quotes.put(name, reply);
	}

	public Reply quote(final String name) {
		Reply quote = quotes.get(name);
		if (quote == null) {
			throw new IllegalStateException("no quote named %s in this scenario".formatted(name));
		}
		return quote;
	}

	public String quoteId(final String name) {
		return quote(name).read("$.data.quoteId");
	}

	public void converted(final String conversionId, final UUID merchantId) {
		conversions.add(conversionId);
		conversionMerchants.put(conversionId, merchantId);
	}

	public UUID merchantOf(final String conversionId) {
		return conversionMerchants.get(conversionId);
	}

	public String conversion() {
		return conversions.getLast();
	}

	public List<String> conversions() {
		return List.copyOf(conversions);
	}

	public List<String> quoteNames() {
		return quotes.keySet().stream().filter(name -> name.startsWith("batch-")).sorted().toList();
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
