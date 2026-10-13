package io.ezeebit.ledger.api.doc;

import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.springdoc.core.customizers.OpenApiCustomizer;

// Mappings carry a {version} segment for API versioning; v1 is the only one served, so the spec names it literally.
public final class PublishedPaths implements OpenApiCustomizer {

	private static final String VERSION = "version";

	private final String version;

	public PublishedPaths(final String supportedVersion) {
		this.version = "v%s".formatted(supportedVersion);
	}

	@Override
	public void customise(final OpenAPI openApi) {
		Paths published = new Paths();
		for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
			entry.getValue().readOperations().forEach(operation -> {
				if (operation.getParameters() != null) {
					operation.getParameters()
							.removeIf(parameter -> VERSION.equals(parameter.getName()) && "path".equals(parameter.getIn()));
				}
			});
			published.addPathItem(entry.getKey().replace("{%s}".formatted(VERSION), version), entry.getValue());
		}
		openApi.setPaths(published);
	}

}
