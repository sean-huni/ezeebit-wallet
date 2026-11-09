package io.ezeebit.ledger.service.conversion.answer;

import java.util.List;
import java.util.Map;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.mapper.ConversionPostingMapper;
import io.ezeebit.ledger.service.CurrencyCatalog;
import org.springframework.stereotype.Component;

@Component
class ConversionPostingAssembler {

	private final EntryRepo entries;
	private final CurrencyCatalog catalog;
	private final ConversionPostingMapper mapper;

	ConversionPostingAssembler(final EntryRepo entries, final CurrencyCatalog catalog,
			final ConversionPostingMapper mapper) {
		this.entries = entries;
		this.catalog = catalog;
		this.mapper = mapper;
	}

	public ConversionPostingResp assemble(final JournalEntity journal) {
		List<StatementLineEntity> lines = entries.merchantLines(journal.id());
		Map<String, Currency> currencies = catalog.all();
		return mapper.toResp(journal, lines, currencies);
	}

}
