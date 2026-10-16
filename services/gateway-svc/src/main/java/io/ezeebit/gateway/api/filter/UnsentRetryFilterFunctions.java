package io.ezeebit.gateway.api.filter;

import java.net.ConnectException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.cloud.gateway.server.mvc.common.Shortcut;
import org.springframework.cloud.gateway.server.mvc.filter.SimpleFilterSupplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;

// The gateway's Retry filter matches only the outermost exception, which is always ResourceAccessException, so it
// cannot tell a connect that failed (nothing sent, safe to resend anywhere) from a read that timed out (maybe applied).
public final class UnsentRetryFilterFunctions {

	private UnsentRetryFilterFunctions() {
	}

	// Sits outside the lb filter, so each attempt chooses an instance again. The body is not cached: the client opens
	// the connection before it reads the request stream, so an attempt that failed to connect left it unread.
	@Shortcut
	public static HandlerFilterFunction<ServerResponse, ServerResponse> retryUnsent(final int attempts) {
		return (request, next) -> {
			for (int attempt = 1;; attempt++) {
				try {
					return next.handle(request);
				}
				catch (ResourceAccessException ex) {
					if (attempt >= attempts || !neverSent(ex)) {
						throw ex;
					}
				}
			}
		};
	}

	// The served config pins the Apache client, whose connect timeout is ConnectTimeoutException. It extends
	// SocketTimeoutException, as a read timeout does, so only the subclass is matched.
	static boolean neverSent(final Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConnectException || cause instanceof ConnectTimeoutException) {
				return true;
			}
		}
		return false;
	}

	public static class FilterSupplier extends SimpleFilterSupplier {

		public FilterSupplier() {
			super(UnsentRetryFilterFunctions.class);
		}

	}

}
