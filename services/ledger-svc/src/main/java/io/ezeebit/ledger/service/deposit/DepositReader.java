package io.ezeebit.ledger.service.deposit;

import java.util.UUID;

import io.ezeebit.ledger.data.model.StatementLineEntity;
import io.ezeebit.ledger.data.repo.EntryRepo;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.mapper.StatementMapper;
import org.springframework.stereotype.Service;

@Service
class DepositReader {

	private final EntryRepo entries;
	private final StatementMapper mapper;

	DepositReader(final EntryRepo entries, final StatementMapper mapper) {
		this.entries = entries;
		this.mapper = mapper;
	}

	public DepositResp read(final UUID journalId, final MerchantId merchant, final Currency currency) {
		StatementLineEntity line = entries.merchantLine(journalId);
		return mapper.toDeposit(line, merchant.value(), currency);
	}

}
