package io.ezeebit.ledger.data.repo;

import io.ezeebit.ledger.data.model.CurrencyEntity;
import org.springframework.data.repository.ListCrudRepository;

public interface CurrencyRepo extends ListCrudRepository<CurrencyEntity, String> {
}
