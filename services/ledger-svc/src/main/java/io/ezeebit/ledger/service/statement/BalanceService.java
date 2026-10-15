package io.ezeebit.ledger.service.statement;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.data.repo.AccountRepo;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.BalanceResp;
import io.ezeebit.ledger.mapper.BalanceMapper;
import io.ezeebit.ledger.service.CurrencyCatalog;
import org.springframework.stereotype.Service;

@Service
public class BalanceService {

	private final AccountRepo accounts;
	private final CurrencyCatalog catalog;
	private final BalanceMapper mapper;

	public BalanceService(final AccountRepo accounts, final CurrencyCatalog catalog, final BalanceMapper mapper) {
		this.accounts = accounts;
		this.catalog = catalog;
		this.mapper = mapper;
	}

	public List<BalanceResp> balances(final UUID merchantId) {
		Map<String, Currency> currencies = catalog.all();
		return accounts.merchantAccounts(merchantId).stream()
				.map(account -> mapper.toResp(account, currencies.get(account.currency())))
				.toList();
	}

}
