package io.ezeebit.ledger.grpc;

import io.ezeebit.contracts.ledger.v1.ConversionPostingsGrpc;
import io.ezeebit.contracts.ledger.v1.PostingRequest;
import io.ezeebit.contracts.ledger.v1.PostingResult;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.service.conversion.ConversionPostingService;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ConversionPostingRpc extends ConversionPostingsGrpc.ConversionPostingsImplBase {

	private final ConversionPostingService postings;
	private final PostingMessages messages;

	public ConversionPostingRpc(final ConversionPostingService postings, final PostingMessages messages) {
		this.postings = postings;
		this.messages = messages;
	}

	@Override
	public void post(final PostingRequest request, final StreamObserver<PostingResult> reply) {
		ConversionPostingReq posting = messages.toRequest(request);
		ConversionPostingResp posted = postings.post(posting);
		PostingResult result = messages.toResult(posted);
		reply.onNext(result);
		reply.onCompleted();
	}

}
