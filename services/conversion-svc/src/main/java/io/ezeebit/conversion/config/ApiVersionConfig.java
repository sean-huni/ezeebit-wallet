package io.ezeebit.conversion.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Every mapping is /api/{version}/... or /internal/{version}/..., so path segment 1 is always the version.
@Configuration(proxyBeanMethods = false)
public class ApiVersionConfig implements WebMvcConfigurer {

	public static final String SUPPORTED_VERSION = "1";

	@Override
	public void configureApiVersioning(final ApiVersionConfigurer configurer) {
		configurer.usePathSegment(1).addSupportedVersions(SUPPORTED_VERSION);
	}

}
