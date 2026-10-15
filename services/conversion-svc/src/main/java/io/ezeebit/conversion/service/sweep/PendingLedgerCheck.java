package io.ezeebit.conversion.service.sweep;

import java.util.Optional;
import java.util.UUID;

import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.domain.Settlement;
import io.ezeebit.conversion.service.conversion.ConversionSettler;
import io.ezeebit.conversion.service.port.LedgerPostings;
import org.springframework.stereotype.Component;

@Component
public class PendingLedgerCheck {

	private final LedgerPostings ledger;
	private final ConversionSettler settler;

	public PendingLedgerCheck(final LedgerPostings ledger, final ConversionSettler settler) {
		this.ledger = ledger;
		this.settler = settler;
	}

	// Found: COMPLETED. Not found: a VOID in the ledger refuses any late posting, then REJECTED. Unknown: try later.
	public Resolution resolve(final ConversionEntity conversion) {
		LedgerAnswer answer = ledger.lookup(conversion.id(), conversion.merchantId());
		if (answer == LedgerAnswer.ABSENT) {
			answer = ledger.voidPosting(conversion.id(), conversion.merchantId());
		}
		return new Resolution(conversion.id(), answer, settler.settle(conversion, answer));
	}

	public record Resolution(UUID id, LedgerAnswer answer, Optional<Settlement> moved) {
	}

}
