package io.ezeebit.conversion.config;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Public mappings are /{version}/merchants/..., internal ones /internal/{version}/.... A public path whose first
// segment is not a version (say /api/v1/...) resolves no version and matches no mapping, so it is 404, not 400.
@Configuration(proxyBeanMethods = false)
public class ApiVersionConfig implements WebMvcConfigurer {

	public static final String SUPPORTED_VERSION = "1";

	private static final String INTERNAL = "internal";
	private static final Pattern VERSION = Pattern.compile("v\\d+");

	@Override
	public void configureApiVersioning(final ApiVersionConfigurer configurer) {
		configurer.useVersionResolver(ApiVersionConfig::version).setVersionRequired(false).addSupportedVersions(SUPPORTED_VERSION);
	}

	static String version(final HttpServletRequest request) {
		String[] segments = request.getRequestURI().split("/");
		if (segments.length > 2 && INTERNAL.equals(segments[1])) {
			return segments[2];
		}
		return segments.length > 1 && VERSION.matcher(segments[1]).matches() ? segments[1] : null;
	}

}
