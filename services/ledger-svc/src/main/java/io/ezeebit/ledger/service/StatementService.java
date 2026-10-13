package io.ezeebit.ledger.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ezeebit.ledger.data.model.StatementFilter;
import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.req.StatementReq;
import io.ezeebit.ledger.dto.resp.StatementResp;
import io.ezeebit.ledger.mapper.StatementMapper;
import org.springframework.stereotype.Service;

@Service
public class StatementService {

	private final EntryRepo entries;
	private final CurrencyCatalog catalog;
	private final StatementMapper mapper;

	public StatementService(final EntryRepo entries, final CurrencyCatalog catalog, final StatementMapper mapper) {
		this.entries = entries;
		this.catalog = catalog;
		this.mapper = mapper;
	}

	public StatementResp statement(final UUID merchantId, final StatementReq request) {
		String requested = request.currency();
		String currency = requested == null ? null : catalog.require(requested).code().value();
		Integer requestedLimit = request.limit();
		int limit = requestedLimit == null ? StatementReq.DEFAULT_LIMIT : requestedLimit;
		Long requestedAfter = request.after();
		long after = requestedAfter == null ? 0L : requestedAfter;
		List<StatementLineEntity> lines = entries.statement(
				new StatementFilter(merchantId, currency, request.from(), request.to(), after, limit));
		Map<String, Currency> currencies = catalog.all();
		String next = lines.size() < limit ? null : String.valueOf(lines.getLast().seq());
		return new StatementResp(lines.stream().map(line -> mapper.toLine(line, currencies.get(line.currency()))).toList(),
				next);
	}

}
