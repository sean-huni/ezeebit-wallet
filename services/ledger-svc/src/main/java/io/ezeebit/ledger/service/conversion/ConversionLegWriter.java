package io.ezeebit.ledger.service.conversion;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.data.repo.AccountRepo;
import io.ezeebit.ledger.domain.AccountType;
import io.ezeebit.ledger.domain.ConversionLegs;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.Posting;
import io.ezeebit.ledger.domain.PostingLine;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.service.posting.EntryWriter;
import org.springframework.stereotype.Component;

// Runs inside ConversionPoster's transaction: four entries and both merchant balances.
@Component
public class ConversionLegWriter {

	// Postgres orders uuid bytewise, which is the order of the lowercase hex text.
	private static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

	private final AccountRepo accounts;
	private final EntryWriter entries;

	public ConversionLegWriter(final AccountRepo accounts, final EntryWriter entries) {
		this.accounts = accounts;
		this.entries = entries;
	}

	public void write(final UUID journalId, final ConversionPostingCommand command) {
		UUID merchantId = command.merchant().value();
		String sellCurrency = command.sell().currency().value();
		String buyCurrency = command.buy().currency().value();
		accounts.openMerchant(UUID.randomUUID(), merchantId, buyCurrency);
		UUID sourceId = accounts.merchantAccountId(merchantId, sellCurrency)
				.orElseThrow(() -> new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, null, sellCurrency));
		UUID targetId = accounts.merchantAccountId(merchantId, buyCurrency).orElseThrow();
		Map<UUID, AccountEntity> locked = lockInOrder(sourceId, targetId);
		Money sourceAfter = new Money(locked.get(sourceId).balance(), command.sell().currency()).debit(command.sell());
		Money targetAfter = new Money(locked.get(targetId).balance(), command.buy().currency()).plus(command.buy());
		Posting posting = Posting.conversion(
				new ConversionLegs(sourceId, fx(sellCurrency), command.sell()),
				new ConversionLegs(targetId, fx(buyCurrency), command.buy()));
		for (PostingLine line : posting.lines()) {
			Money after = line.accountId().equals(sourceId) ? sourceAfter
					: line.accountId().equals(targetId) ? targetAfter : null;
			entries.write(journalId, line, after);
		}
		accounts.setBalance(sourceId, sourceAfter.amount());
		accounts.setBalance(targetId, targetAfter.amount());
	}

	// Ascending id order: two postings touching the same pair of accounts can never deadlock.
	private Map<UUID, AccountEntity> lockInOrder(final UUID first, final UUID second) {
		Map<UUID, AccountEntity> locked = new HashMap<>();
		for (UUID id : List.of(first, second).stream().sorted(LOCK_ORDER).toList()) {
			locked.put(id, accounts.lockById(id));
		}
		return locked;
	}

	private UUID fx(final String currency) {
		return accounts.platformAccount(currency, AccountType.PLATFORM_FX.name());
	}

}
