package io.ezeebit.ledger.mapper;

import java.util.List;
import java.util.Map;

import io.ezeebit.ledger.data.model.JournalEntity;
import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;

public interface ConversionPostingMapper {

	ConversionPostingResp toResp(JournalEntity journal, List<StatementLineEntity> merchantLines,
			Map<String, Currency> currencies);

}
