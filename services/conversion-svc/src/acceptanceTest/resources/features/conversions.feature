Feature: A merchant converts at exactly the quoted price
  Accepting a quote moves money in the ledger in one transaction. The merchant gets exactly the
  quoted amounts, or a clear refusal. Requests arrive over flaky connections, sometimes twice.

  Background:
    Given a merchant "Acme" holding "1000.00" ZAR

  Scenario: The merchant gets exactly the quoted rate
    Given "Acme" has quote "q1" to sell "1000.00" ZAR for USDT
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is COMPLETED
    And "Acme" holds exactly:
      | currency | balance   |
      | USDT     | 53.982730 |
      | ZAR      | 0.00      |
    And the statement of "Acme" shows the conversion of quote "q1" at exactly the quoted amounts
    And the ledger's books balance

  Scenario: An expired quote cannot be used
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    And quote "q1" has expired
    When "Acme" accepts quote "q1" with key "k1"
    Then the request is refused with 409 QUOTE_EXPIRED
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |

  Scenario: A quote cannot be used twice
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    And "Acme" has accepted quote "q1" with key "k1"
    When "Acme" accepts quote "q1" with key "k2"
    Then the request is refused with 409 QUOTE_ALREADY_USED
    And "Acme" holds "900.00" ZAR

  Scenario: A merchant cannot use another merchant's quote
    Given a merchant "Bistro"
    And "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    When "Bistro" accepts quote "q1" with key "k1"
    Then the request is refused with 404 QUOTE_NOT_FOUND

  Scenario: A duplicate conversion request converts once and answers the same
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    And "Acme" has accepted quote "q1" with key "k1"
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is COMPLETED
    And both answers are identical
    And the ledger holds exactly 1 conversion for "Acme"
    And "Acme" holds "900.00" ZAR

  Scenario: The same key for a different quote is refused
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    And "Acme" has quote "q2" to sell "100.00" ZAR for USDT
    And "Acme" has accepted quote "q1" with key "k1"
    When "Acme" accepts quote "q2" with key "k1"
    Then the request is refused with 422 IDEMPOTENCY_KEY_REUSED

  Scenario: A merchant cannot convert more than it holds
    Given "Acme" has quote "q1" to buy "100.000000" USDT with ZAR
    When "Acme" accepts quote "q1" with key "k1"
    Then the request is refused with 422 INSUFFICIENT_FUNDS
    And the conversion shows status REJECTED because INSUFFICIENT_FUNDS
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |

  Scenario: A conversion request without an idempotency key is refused
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    When "Acme" accepts quote "q1" without an idempotency key
    Then the request is refused with 400 IDEMPOTENCY_KEY_MISSING

  Scenario: An unknown conversion is not found
    When "Acme" looks up a conversion that does not exist
    Then the request is refused with 404 CONVERSION_NOT_FOUND
