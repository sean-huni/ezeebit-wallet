package io.ezeebit.conversion.service.conversion;

import java.time.Instant;
import java.util.UUID;

import io.ezeebit.conversion.dto.resp.ConversionResp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

// Not transactional itself: a duplicate key must be caught outside the transaction it rolled back.
@Component
public class ConversionOpener {

	private final ConversionStarter starter;
	private final ConversionReplayer replayer;
	private final ConversionFirstPoster firstPoster;

	public ConversionOpener(final ConversionStarter starter, final ConversionReplayer replayer,
			final ConversionFirstPoster firstPoster) {
		this.starter = starter;
		this.replayer = replayer;
		this.firstPoster = firstPoster;
	}

	public ConversionResp open(final UUID merchantId, final String key, final UUID quoteId, final Instant deadline) {
		try {
			starter.start(merchantId, key, quoteId, deadline);
		}
		catch (DuplicateKeyException duplicate) {
			return replayer.afterDuplicate(merchantId, key, quoteId);
		}
		return firstPoster.post(merchantId, key);
	}

}
