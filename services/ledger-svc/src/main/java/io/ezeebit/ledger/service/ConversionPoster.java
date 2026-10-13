package io.ezeebit.ledger.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.AccountRepo;
import io.ezeebit.ledger.data.repo.DbClockRepo;
import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.AccountType;
import io.ezeebit.ledger.domain.ConversionLegs;
import io.ezeebit.ledger.domain.JournalStatus;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.Posting;
import io.ezeebit.ledger.domain.PostingLine;
import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.mapper.EntryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// One conversion, one transaction: journal, four entries and both merchant balances commit together or not at all.
@Service
public class ConversionPoster {

	// Postgres orders uuid bytewise, which is the order of the lowercase hex text.
	private static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

	private final JournalRepo journals;
	private final AccountRepo accounts;
	private final EntryRepo entries;
	private final DbClockRepo clock;
	private final EntryMapper mapper;

	public ConversionPoster(final JournalRepo journals, final AccountRepo accounts, final EntryRepo entries,
			final DbClockRepo clock, final EntryMapper mapper) {
		this.journals = journals;
		this.accounts = accounts;
		this.entries = entries;
		this.clock = clock;
		this.mapper = mapper;
	}

	// The journal goes in first: a duplicate or a VOID marker under this identity fails here, before any lock.
	@Transactional
	public UUID post(final ConversionPostingCommand command) {
		UUID journalId = UUID.randomUUID();
		UUID merchantId = command.merchant().value();
		String sellCurrency = command.sell().currency().value();
		String buyCurrency = command.buy().currency().value();
		journals.insert(new JournalEntity(journalId, merchantId, JournalType.CONVERSION.name(),
				JournalStatus.POSTED.name(), command.conversionId().toString(), command.requestHash(),
				"conversion %s".formatted(command.conversionId()), null));
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
			entries.insert(mapper.toEntity(journalId, line, after));
		}
		accounts.setBalance(sourceId, sourceAfter.amount());
		accounts.setBalance(targetId, targetAfter.amount());
		// Last, so nothing that commits was decided after the deadline; the exception rolls everything back.
		if (clock.now().isAfter(command.deadline())) {
			throw new LedgerException(ErrorCode.POSTING_DEADLINE_PASSED, "deadline", command.deadline().toString());
		}
		return journalId;
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
