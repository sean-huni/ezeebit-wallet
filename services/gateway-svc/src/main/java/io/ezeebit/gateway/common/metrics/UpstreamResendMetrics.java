package io.ezeebit.gateway.common.metrics;

public interface UpstreamResendMetrics {

	String NAME = "ezeebit.gateway.upstream.resend";

	void resent(String route);

}
