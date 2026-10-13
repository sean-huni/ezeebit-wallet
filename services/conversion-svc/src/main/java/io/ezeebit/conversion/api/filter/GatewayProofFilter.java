package io.ezeebit.conversion.api.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import io.ezeebit.conversion.exception.ConversionException;
import io.ezeebit.conversion.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// Defence in depth, not authentication: a public merchant route answers only a request the gateway forwarded.
// The servlet path is the container's normalised, decoded path, so ;params and dot segments cannot step around it.
public final class GatewayProofFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Ezeebit-Gateway";

	private static final Pattern MERCHANT_ROUTE = Pattern.compile("^/[^/]*/merchants(/.*)?$");

	private final byte[] secret;
	private final HandlerExceptionResolver refusals;

	public GatewayProofFilter(final String secret, final HandlerExceptionResolver refusals) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("ezeebit.gateway.shared-secret is blank: every public route would be open");
		}
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.refusals = refusals;
	}

	@Override
	protected boolean shouldNotFilter(final HttpServletRequest request) {
		return !MERCHANT_ROUTE.matcher(request.getServletPath()).matches();
	}

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
			throws ServletException, IOException {
		String proof = request.getHeader(HEADER);
		// Constant time: an early exit on the first differing byte would let response timing reveal the secret.
		if (proof != null && MessageDigest.isEqual(secret, proof.getBytes(StandardCharsets.UTF_8))) {
			chain.doFilter(request, response);
			return;
		}
		refusals.resolveException(request, response, null, new ConversionException(ErrorCode.GATEWAY_REQUIRED, null));
	}

}
