Feature: Many requests at once never spend more than the balance
  Paydays bring bursts, and the dashboard fires requests more than once.

  Background:
    Given a merchant "Acme" holding "1000.00" ZAR

  Scenario: Concurrent conversions against one balance: exactly as many as it covers succeed
    Given "Acme" has 8 quotes each to sell "300.00" ZAR, alternately for USDT and USDC
    When "Acme" accepts all its quotes at the same time, each with its own key
    Then exactly 3 conversions complete and the rest are refused with 422 INSUFFICIENT_FUNDS
    And "Acme" holds "100.00" ZAR
    And the ledger's books balance

  Scenario: The same request sent five times at once converts once
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    When "Acme" accepts quote "q1" 5 times at the same time with key "k1"
    Then every answer is COMPLETED and identical
    And the ledger holds exactly 1 conversion for "Acme"
    And "Acme" holds "900.00" ZAR

  Scenario: One quote accepted at once under two keys is used once
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    When "Acme" accepts quote "q1" at the same time with keys "k1" and "k2"
    Then one answer is COMPLETED and the other is refused with 409 QUOTE_ALREADY_USED
    And the ledger holds exactly 1 conversion for "Acme"
    And "Acme" holds "900.00" ZAR
