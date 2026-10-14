package io.ezeebit.conversion.outbound.ledger;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.conversion.common.log.LogText;
import io.ezeebit.conversion.domain.LedgerAnswer;
import io.ezeebit.conversion.service.port.LedgerPosting;
import io.ezeebit.conversion.service.port.LedgerPostings;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

// Any transport failure is UNKNOWN: the posting may have committed.
@Component
public class LedgerGrpcClient implements LedgerPostings {

	private static final Logger LOG = LoggerFactory.getLogger(LedgerGrpcClient.class);
	private static final long ATTEMPT_MILLIS = 2_000;

	private final ManagedChannel channel;
	private final LedgerReplyMapper replies;

	public LedgerGrpcClient(@Qualifier("ledgerChannel") final ManagedChannel channel, final LedgerReplyMapper replies) {
		this.channel = channel;
		this.replies = replies;
	}

	// Exactly the quoted amounts, at each currency's scale: the merchant gets the quoted rate or nothing.
	@Override
	public LedgerAnswer post(final LedgerPosting posting) {
		PostingRequest message = PostingRequest.newBuilder().setConversionId(posting.conversionId().toString())
				.setMerchantId(posting.merchantId().toString())
				.setSellCurrency(posting.quote().sell().currency().code()).setSellAmount(posting.quote().sell().text())
				.setBuyCurrency(posting.quote().buy().currency().code()).setBuyAmount(posting.quote().buy().text())
				.setDeadline(posting.deadline().toString()).build();
		return call(() -> ConversionPostingsGrpc.newBlockingStub(channel)
				.withDeadlineAfter(ATTEMPT_MILLIS, TimeUnit.MILLISECONDS).post(message));
	}

	@Override
	public LedgerAnswer lookup(final UUID conversionId, final UUID merchantId) {
		return aboutTheConversion(call(() -> settlement().find(lookupMessage(conversionId, merchantId))));
	}

	@Override
	public LedgerAnswer voidPosting(final UUID conversionId, final UUID merchantId) {
		return aboutTheConversion(call(() -> settlement().void_(lookupMessage(conversionId, merchantId))));
	}

	// A refused lookup or void is about this caller's message, never the conversion: the outcome stays open.
	private static LedgerAnswer aboutTheConversion(final LedgerAnswer answer) {
		return answer == LedgerAnswer.INPUT_REFUSED ? LedgerAnswer.UNKNOWN : answer;
	}

	private ConversionSettlementGrpc.ConversionSettlementBlockingStub settlement() {
		return ConversionSettlementGrpc.newBlockingStub(channel).withDeadlineAfter(ATTEMPT_MILLIS, TimeUnit.MILLISECONDS);
	}

	private static PostingLookup lookupMessage(final UUID conversionId, final UUID merchantId) {
		return PostingLookup.newBuilder().setConversionId(conversionId.toString()).setMerchantId(merchantId.toString())
				.build();
	}

	private LedgerAnswer call(final Attempt attempt) {
		try {
			return replies.fromResult(attempt.run());
		}
		catch (StatusRuntimeException ex) {
			LedgerAnswer answer = replies.fromStatus(ex);
			if (answer == LedgerAnswer.UNKNOWN) {
				LOG.warn("ledger outcome unknown: {}", LogText.oneLine(ex.getMessage()));
			}
			return answer;
		}
	}

	@FunctionalInterface
	interface Attempt {

		PostingResult run();

	}

}
