Feature: Deposits stay exact under load
  Paydays bring bursts. Concurrent deposits to one account must sum exactly, and a duplicate
  burst must still credit once.

  Background:
    Given a merchant "Acme"

  Scenario: Fifty deposits at once sum exactly
    When the rail sends 50 deposits of "1.01" ZAR for "Acme" at the same time, each with its own key
    Then every answer is accepted
    And "Acme" holds "50.50" ZAR
    And the ZAR balance of "Acme" equals the sum of its statement
    And every journal sums to zero in each currency

  Scenario: Twenty copies of one deposit at once credit once
    When the rail sends the same deposit of "10.00" ZAR for "Acme" 20 times at the same time with key "t-1"
    Then every answer is accepted
    And every answer is identical
    And exactly 1 deposit is recorded for "Acme" with key "t-1"
    And "Acme" holds "10.00" ZAR

  Scenario: One key sent at once in two currencies posts exactly one deposit
    When the rail sends "10.00" ZAR and "5.000000" USDT for "Acme" at the same time, both with key "t-1"
    Then exactly one answer is accepted and the other is refused with 422 IDEMPOTENCY_KEY_REUSED
    And exactly 1 deposit is recorded for "Acme" with key "t-1"
    And "Acme" holds only the accepted deposit

  Scenario: A new merchant's first deposits in two currencies at once open both accounts correctly
    When the rail sends 10 deposits of "1.00" ZAR and 10 deposits of "0.500000" USDT for "Acme" at the same time
    Then every answer is accepted
    And "Acme" holds exactly:
      | currency | balance  |
      | USDT     | 5.000000 |
      | ZAR      | 10.00    |
    And every journal sums to zero in each currency
    And every merchant balance equals the sum of its entries
