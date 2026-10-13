package io.ezeebit.ledger.acceptance;

import com.jayway.jsonpath.JsonPath;

public record Reply(int status, String body) {

	public <T> T read(final String path) {
		return JsonPath.read(body, path);
	}

}
