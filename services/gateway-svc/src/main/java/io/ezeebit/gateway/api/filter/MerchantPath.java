package io.ezeebit.gateway.api.filter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Decided on the raw URI only after it is proven canonical, so the raw path IS the decoded, normalised path the
// route matcher sees: no %xx, no ;params, no dot segments, no //. Every route lives under /api/ (RouteCoverageIT).
public record MerchantPath(Kind kind, String key) {

	public enum Kind { NOT_MERCHANT, MALFORMED, INVALID_ID, MERCHANT }

	private static final String ROUTED = "/api/";

	private static final Pattern CANONICAL = Pattern.compile("/[A-Za-z0-9_./-]*");
	private static final Pattern DOT_SEGMENT = Pattern.compile("/\\.{1,2}(/|$)");
	private static final Pattern MERCHANT_ROUTE = Pattern.compile("^/api/v1/merchants/([^/]*)(/.*)?$");
	// Strict: UUID.fromString also accepts short forms such as 1-1-1-1-1, which would make many keys per merchant.
	private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	public static MerchantPath of(final String rawUri) {
		if (!CANONICAL.matcher(rawUri).matches() || rawUri.contains("//") || DOT_SEGMENT.matcher(rawUri).find()) {
			return new MerchantPath(Kind.MALFORMED, null);
		}
		if (!rawUri.startsWith(ROUTED)) {
			return new MerchantPath(Kind.NOT_MERCHANT, null);
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
