package io.ezeebit.ledger.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;

// A balanced set of entries: per currency the lines sum to zero, so money is moved, never made.
public record Posting(List<PostingLine> lines) {

	public Posting {
		lines = List.copyOf(lines);
		if (lines.size() < 2) {
			throw new LedgerException(ErrorCode.UNBALANCED_POSTING, null, lines.size());
		}
		Map<CurrencyCode, Money> sums = new LinkedHashMap<>();
		lines.forEach(line -> sums.merge(line.money().currency(), line.money(), Money::plus));
		sums.values().stream().filter(sum -> !sum.isZero()).findFirst().ifPresent(sum -> {
			throw new LedgerException(ErrorCode.UNBALANCED_POSTING, null, sum.currency().value());
		});
	}

	public static Posting deposit(final UUID merchantAccount, final UUID clearingAccount, final Money amount) {
		return new Posting(List.of(new PostingLine(merchantAccount, amount),
				new PostingLine(clearingAccount, amount.negate())));
	}

}
