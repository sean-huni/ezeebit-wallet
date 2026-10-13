package io.ezeebit.conversion.acceptance;

import com.jayway.jsonpath.JsonPath;

public record Reply(int status, String body, long millis) {

	public <T> T read(final String path) {
		return JsonPath.read(body, path);
	}

}
