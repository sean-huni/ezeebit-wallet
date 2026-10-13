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

  Scenario: Twenty copies of one deposit at once credit once
    When the rail sends the same deposit of "10.00" ZAR for "Acme" 20 times at the same time with key "t-1"
    Then every answer is accepted
    And every answer is identical
    And exactly 1 deposit is recorded for "Acme" with key "t-1"
    And "Acme" holds "10.00" ZAR
