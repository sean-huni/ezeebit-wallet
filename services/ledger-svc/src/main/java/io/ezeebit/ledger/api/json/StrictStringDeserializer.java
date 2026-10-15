package io.ezeebit.ledger.api.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// Jackson would turn a JSON number into its text; for money only a JSON string is accepted.
public class StrictStringDeserializer extends ValueDeserializer<String> {

	@Override
	public String deserialize(final JsonParser parser, final DeserializationContext context) {
		if (parser.currentToken() != JsonToken.VALUE_STRING) {
			return (String) context.handleUnexpectedToken(String.class, parser);
		}
		return parser.getString();
	}

}
