package io.ezeebit.gateway.common;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The raw request URI, read before routing. The route matcher ignores ;params and decodes %xx, so a path is
// limited only in its canonical form: otherwise one merchant's request could be charged to another's bucket.
public record MerchantPath(Kind kind, String key) {

	public enum Kind { NOT_MERCHANT, MALFORMED, INVALID_ID, MERCHANT }

	static final String API = "/api/";

	private static final Pattern CANONICAL = Pattern.compile("[A-Za-z0-9_/-]*");
	private static final Pattern MERCHANT_ROUTE = Pattern.compile("^/api/v1/merchants/([^/]*)(/.*)?$");
	// Strict: UUID.fromString also accepts short forms such as 1-1-1-1-1, which would make many keys per merchant.
	private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	public static MerchantPath of(final String rawUri) {
		if (!rawUri.startsWith(API)) {
			return new MerchantPath(Kind.NOT_MERCHANT, null);
		}
		if (!CANONICAL.matcher(rawUri).matches() || rawUri.contains("//")) {
			return new MerchantPath(Kind.MALFORMED, null);
		}
		Matcher route = MERCHANT_ROUTE.matcher(rawUri);
		if (!route.matches()) {
			return new MerchantPath(Kind.NOT_MERCHANT, null);
		}
		String id = route.group(1);
		if (!UUID.matcher(id).matches()) {
			return new MerchantPath(Kind.INVALID_ID, null);
		}
		return new MerchantPath(Kind.MERCHANT, "merchant:%s".formatted(id.toLowerCase(Locale.ROOT)));
	}

}
