package io.ezeebit.ledger.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

// SHA-256 over the request's fields as sent. Each part is length-prefixed, so no part can forge a boundary.
@Component
public class RequestFingerprint {

	public String of(final String... parts) {
		StringBuilder framed = new StringBuilder();
		for (String part : parts) {
			framed.append("%d:%s".formatted(part.length(), part));
		}
		return HexFormat.of().formatHex(sha256().digest(framed.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is mandatory on every JVM", ex);
		}
	}

}
