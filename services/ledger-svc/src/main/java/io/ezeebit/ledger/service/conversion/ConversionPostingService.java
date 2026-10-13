package io.ezeebit.ledger.service.conversion;

import io.ezeebit.ledger.domain.MerchantId;
import io.ezeebit.ledger.dto.req.ConversionPostingReq;
import io.ezeebit.ledger.dto.resp.ConversionPostingResp;
import io.ezeebit.ledger.service.conversion.answer.ConversionPostingResponder;
import org.springframework.stereotype.Service;

@Service
public class ConversionPostingService {

	private final ConversionPostingIntake intake;
	private final ConversionPostingRecorder recorder;
	private final ConversionPostingResponder responder;

	public ConversionPostingService(final ConversionPostingIntake intake, final ConversionPostingRecorder recorder,
			final ConversionPostingResponder responder) {
		this.intake = intake;
		this.recorder = recorder;
		this.responder = responder;
	}

	public ConversionPostingResp post(final ConversionPostingReq request) {
		return responder.respond("post", () -> recorder.record(intake.command(request), request),
				new MerchantId(request.merchantId()), request.conversionId());
	}

}
