Feature: One request is one trace, from the caller through the rate feed and the ledger
  The gateway forwards its W3C trace context; conversion-svc must carry it on to the rate feed and the
  ledger, or Tempo shows three disconnected traces where an operator needs one.

  Background:
    Given a merchant "Acme" holding "1000.00" ZAR

  Scenario: A quote and its conversion carry the caller's trace to the rate feed and the ledger
    When "Acme" asks for quote "q1" to sell "100.00" ZAR for USDT inside trace "4bf92f3577b34da6a3ce929d0e0e4736"
    Then the rate feed was called inside trace "4bf92f3577b34da6a3ce929d0e0e4736"
    When "Acme" accepts quote "q1" with key "k1" inside trace "0af7651916cd43dd8448eb211c80319c"
    Then the conversion is COMPLETED
    And the ledger was called inside trace "0af7651916cd43dd8448eb211c80319c"

  Scenario: A refused quote still carries the caller's trace to the rate feed that failed
    Given the rate service fails with status 500
    When "Acme" asks for quote "q1" to sell "100.00" ZAR for USDT inside trace "5a1c2e3f4b5d6e7f8091a2b3c4d5e6f7"
    Then the request is refused with 503 RATE_UNAVAILABLE
    And the rate feed was called inside trace "5a1c2e3f4b5d6e7f8091a2b3c4d5e6f7"

  Scenario: A request that arrives with no trace starts one and still propagates it
    When "Acme" asks for quote "q1" to sell "100.00" ZAR for USDT
    Then every rate feed call carried a trace context
