package io.ezeebit.ledger.mapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import org.springframework.stereotype.Component;

@Component
public class ConversionPostingMapper {

	private final AmountFormat format;

	public ConversionPostingMapper(final AmountFormat format) {
		this.format = format;
	}

	// The merchant's debit is the sell side and its credit the buy side; a VOID journal has neither.
	public ConversionPostingResp toResp(final JournalEntity journal, final List<StatementLineEntity> merchantLines,
			final Map<String, Currency> currencies) {
		StatementLineEntity sell = merchantLines.stream().filter(line -> line.amount().signum() < 0).findFirst()
				.orElse(null);
		StatementLineEntity buy = merchantLines.stream().filter(line -> line.amount().signum() > 0).findFirst()
				.orElse(null);
		return new ConversionPostingResp(journal.id(), UUID.fromString(journal.idempotencyKey()), journal.merchantId(),
				journal.status(), sell == null ? null : sell.currency(),
				sell == null ? null : format.format(sell.amount().negate(), currencies.get(sell.currency()).scale()),
				buy == null ? null : buy.currency(),
				buy == null ? null : format.format(buy.amount(), currencies.get(buy.currency()).scale()),
				journal.created());
	}

}
