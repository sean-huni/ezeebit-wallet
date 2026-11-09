package io.ezeebit.ledger.data.repo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.ezeebit.ledger.data.model.AccountEntity;

public interface AccountRepo {

	void openMerchant(UUID id, UUID merchantId, String currency);

	AccountEntity lockMerchant(UUID merchantId, String currency);

	Optional<UUID> merchantAccountId(UUID merchantId, String currency);

	AccountEntity lockById(UUID id);

	UUID platformAccount(String currency, String type);

	void setBalance(UUID id, BigDecimal balance);

	List<AccountEntity> merchantAccounts(UUID merchantId);

}
