package io.ezeebit.ledger.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("currency")
public record CurrencyEntity(@Id String code, String kind, int scale) {
}
