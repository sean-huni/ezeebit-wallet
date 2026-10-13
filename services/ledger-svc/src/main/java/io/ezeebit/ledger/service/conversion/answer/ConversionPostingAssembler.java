package io.ezeebit.ledger.service.conversion.answer;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.mapper.ConversionPostingMapper;
import io.ezeebit.ledger.service.CurrencyCatalog;
import org.springframework.stereotype.Component;

@Component
public class ConversionPostingAssembler {

	private final EntryRepo entries;
	private final CurrencyCatalog catalog;
	private final ConversionPostingMapper mapper;

	public ConversionPostingAssembler(final EntryRepo entries, final CurrencyCatalog catalog,
			final ConversionPostingMapper mapper) {
		this.entries = entries;
		this.catalog = catalog;
		this.mapper = mapper;
	}

	public ConversionPostingResp assemble(final JournalEntity journal) {
		return mapper.toResp(journal, entries.merchantLines(journal.id()), catalog.all());
	}

}
