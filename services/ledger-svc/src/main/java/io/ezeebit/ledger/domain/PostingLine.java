package io.ezeebit.ledger.domain;

import java.util.Objects;
import java.util.UUID;

public record PostingLine(UUID accountId, Money money) {

	public PostingLine {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(money, "money");
	}

}
