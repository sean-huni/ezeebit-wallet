package io.ezeebit.ledger.grpc;

import io.ezeebit.contracts.ledger.v1.ConversionSettlementGrpc;
import io.ezeebit.contracts.ledger.v1.PostingLookup;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.ledger.service.conversion.ConversionVoidService;
import io.ezeebit.ledger.service.conversion.answer.ConversionPostingReader;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ConversionSettlementRpc extends ConversionSettlementGrpc.ConversionSettlementImplBase {

	private final ConversionVoidService voids;
	private final ConversionPostingReader reader;
	private final PostingMessages messages;

	public ConversionSettlementRpc(final ConversionVoidService voids, final ConversionPostingReader reader,
			final PostingMessages messages) {
		this.voids = voids;
		this.reader = reader;
		this.messages = messages;
	}

	@Override
	public void find(final PostingLookup lookup, final StreamObserver<PostingResult> reply) {
		reply.onNext(messages.toResult(reader.find(messages.uuid(lookup.getConversionId(), "conversionId"),
				messages.uuid(lookup.getMerchantId(), "merchantId"))));
		reply.onCompleted();
	}

	@Override
	public void void_(final PostingLookup lookup, final StreamObserver<PostingResult> reply) {
		reply.onNext(messages.toResult(voids.voidPosting(messages.uuid(lookup.getConversionId(), "conversionId"),
				messages.uuid(lookup.getMerchantId(), "merchantId"))));
		reply.onCompleted();
	}

}
