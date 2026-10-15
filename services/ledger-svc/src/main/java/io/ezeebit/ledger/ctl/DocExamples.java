package io.ezeebit.ledger.ctl;

// Values shared by the spec annotations: the README demo's merchant and wording, so the spec and the demo agree.
final class DocExamples {

	static final String MERCHANT = "3f2c8a10-5b7e-4c1d-9a2e-6d4f8b0c1e27";
	static final String MERCHANT_ID = "Merchant id, a UUID.";
	static final String IDEMPOTENCY_KEY = "Unique per deposit, at most 128 characters. The same key with a different body is 422.";

	private DocExamples() {
	}

}
