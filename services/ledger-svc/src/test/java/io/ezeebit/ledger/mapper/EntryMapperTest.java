package io.ezeebit.ledger.mapper;

import static io.ezeebit.ledger.mapper.Fixtures.ZAR;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import io.ezeebit.ledger.data.model.EntryEntity;
import io.ezeebit.ledger.domain.Money;
import io.ezeebit.ledger.domain.PostingLine;
import org.junit.jupiter.api.Test;

class EntryMapperTest {

	private final EntryMapper mapper = new EntryMapper();
	private final UUID journal = UUID.randomUUID();
	private final UUID account = UUID.randomUUID();

	@Test
	void merchantLineCarriesItsBalanceAfter() {
		EntryEntity entity = mapper.toEntity(journal, new PostingLine(account, Money.parse("10.00", ZAR)),
				Money.parse("35.00", ZAR));

		assertThat(entity).usingRecursiveComparison().ignoringFields("id").isEqualTo(
				new EntryEntity(null, journal, account, "ZAR", new BigDecimal("10.00"), new BigDecimal("35.00")));
	}

	@Test
	void platformLineHasNoBalanceAfter() {
		EntryEntity entity = mapper.toEntity(journal, new PostingLine(account, Money.parse("10.00", ZAR).negate()), null);

		assertThat(entity.balanceAfter()).isNull();
	}

	@Test
	void everyEntryGetsItsOwnId() {
		PostingLine line = new PostingLine(account, Money.parse("10.00", ZAR));

		assertThat(mapper.toEntity(journal, line, null).id()).isNotEqualTo(mapper.toEntity(journal, line, null).id());
	}

}
