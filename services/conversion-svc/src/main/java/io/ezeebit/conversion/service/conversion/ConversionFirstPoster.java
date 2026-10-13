package io.ezeebit.conversion.service.conversion;

import java.util.UUID;

import io.ezeebit.conversion.common.metrics.ConversionMetrics;
import io.ezeebit.conversion.data.model.ConversionEntity;
import io.ezeebit.conversion.domain.ConversionStatus;
import io.ezeebit.conversion.dto.resp.ConversionResp;
import org.springframework.stereotype.Component;

@Component
public class ConversionFirstPoster {

	private final ConversionReader reader;
	private final ConversionLedgerPoster poster;
	private final ConversionMetrics metrics;

	public ConversionFirstPoster(final ConversionReader reader, final ConversionLedgerPoster poster,
			final ConversionMetrics metrics) {
		this.reader = reader;
		this.poster = poster;
		this.metrics = metrics;
	}

	public ConversionResp post(final UUID merchantId, final String key) {
		ConversionEntity first = poster.postAndSettle(reader.byKey(merchantId, key).orElseThrow());
		// A settled outcome is counted where it is settled; a first answer that left it open is counted here.
		if (ConversionStatus.PENDING.name().equals(first.status())) {
			metrics.leftPending();
		}
		return reader.answer(first);
	}

}
