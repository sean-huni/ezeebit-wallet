package io.ezeebit.ledger.service.deposit;

import io.ezeebit.ledger.common.metrics.LedgerMetrics;
import io.ezeebit.ledger.common.metrics.LedgerMetrics.Outcome;
import io.ezeebit.ledger.domain.Currency;
import io.ezeebit.ledger.dto.req.DepositReq;
import io.ezeebit.ledger.dto.resp.DepositResp;
import io.ezeebit.ledger.exception.LedgerException;
import io.ezeebit.ledger.service.deposit.DepositRecorder.Recorded;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DepositService {

	private static final Logger LOG = LoggerFactory.getLogger(DepositService.class);

	private final DepositIntake intake;
	private final DepositRecorder recorder;
	private final LedgerMetrics metrics;

	public DepositService(final DepositIntake intake, final DepositRecorder recorder, final LedgerMetrics metrics) {
		this.intake = intake;
		this.recorder = recorder;
		this.metrics = metrics;
	}

	public DepositResp deposit(final String idempotencyKey, final DepositReq request) {
		Observation span = metrics.depositSpan();
		return metrics.observe(span, () -> post(span, idempotencyKey, request));
	}

	private DepositResp post(final Observation span, final String idempotencyKey, final DepositReq request) {
		Currency currency = null;
		try {
			currency = intake.currency(request);
			Recorded recorded = recorder.record(intake.command(idempotencyKey, request, currency), currency);
			metrics.deposit(span, currency, recorded.outcome(), null);
			LOG.info("deposit {} in {}", recorded.outcome(), currency.code().value());
			return recorded.answer();
		}
		catch (LedgerException refusal) {
			metrics.deposit(span, currency, Outcome.REFUSED, refusal.code());
			LOG.info("deposit refused: {}", refusal.code());
			throw refusal;
		}
	}

}
