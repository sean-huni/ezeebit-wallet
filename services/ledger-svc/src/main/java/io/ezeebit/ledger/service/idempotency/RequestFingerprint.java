package io.ezeebit.ledger.service.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public interface RequestFingerprint {

	String of(String... parts);

	// Constant time, so response timing does not reveal how much of a stored fingerprint a guess matched.
	static boolean sameHash(final String stored, final String candidate) {
		return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
	}

}
