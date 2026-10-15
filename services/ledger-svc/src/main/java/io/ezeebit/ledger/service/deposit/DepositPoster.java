package io.ezeebit.ledger.service.deposit;

import java.util.UUID;

import io.ezeebit.ledger.data.model.AccountEntity;
import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.AccountRepo;
import io.ezeebit.ledger.data.repo.JournalRepo;
import io.ezeebit.ledger.domain.AccountType;
import io.ezeebit.ledger.domain.JournalType;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.Posting;
import io.ezeebit.ledger.domain.PostingLine;
import io.ezeebit.ledger.service.posting.EntryWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// One deposit, one transaction: journal, both entries and the cached balance commit together or not at all.
@Service
public class DepositPoster {

	private static final String POSTED = "POSTED";

	private final JournalRepo journals;
	private final AccountRepo accounts;
	private final EntryWriter entries;

	public DepositPoster(final JournalRepo journals, final AccountRepo accounts, final EntryWriter entries) {
		this.journals = journals;
		this.accounts = accounts;
		this.entries = entries;
	}

	// The journal goes in first, so a duplicate key fails here, before any account is touched.
	@Transactional
	public UUID post(final DepositCommand command) {
		UUID journalId = UUID.randomUUID();
		UUID merchantId = command.merchant().value();
		String currency = command.amount().currency().value();
		journals.insert(new JournalEntity(journalId, merchantId, JournalType.DEPOSIT.name(), POSTED,
				command.idempotencyKey(), command.requestHash(), command.reference(), null, null));
		accounts.openMerchant(UUID.randomUUID(), merchantId, currency);
		AccountEntity account = accounts.lockMerchant(merchantId, currency);
		UUID clearing = accounts.platformAccount(currency, AccountType.PLATFORM_DEPOSIT_CLEARING.name());
		Posting posting = Posting.deposit(account.id(), clearing, command.amount());
		// plus() refuses a sum the balance column cannot hold; the exception rolls the journal back too.
		Money balanceAfter = new Money(account.balance(), command.amount().currency()).plus(command.amount());
		for (PostingLine line : posting.lines()) {
			entries.write(journalId, line, line.accountId().equals(account.id()) ? balanceAfter : null);
		}
		accounts.setBalance(account.id(), balanceAfter.amount());
		return journalId;
	}

}
