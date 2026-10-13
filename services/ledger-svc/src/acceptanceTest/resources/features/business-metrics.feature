Feature: The ledger counts what it does with money
  Operators read deposits and conversion postings on the Money row of Fleet Overview. Every answer
  is counted by its outcome and, for a refusal, by its code. No tag carries a merchant or an id.

  Background:
    Given a merchant "Acme"

  Scenario: Deposits are counted by currency and outcome
    When the rail deposits "100.00" ZAR for "Acme" with key "t-1"
    And the rail sends the deposit with key "t-1" again
    And the rail deposits "99999999999999999999.99" ZAR for "Acme" with key "t-2"
    And the rail deposits "1.00" XYZ for "Acme" with key "t-3"
    Then the ledger counted these deposits:
      | currency | outcome  | code                   | count |
      | ZAR      | posted   | none                   | 1     |
      | ZAR      | replayed | none                   | 1     |
      | ZAR      | refused  | BALANCE_LIMIT_EXCEEDED | 1     |
      | unknown  | refused  | UNKNOWN_CURRENCY       | 1     |

  Scenario: Conversion postings are counted by outcome
    Given the rail has deposited "1000.00" ZAR for "Acme" with key "t-1"
    When conversion "c-1" for "Acme" sells "184.32" ZAR for "10.000000" USDT with a deadline 1 minute away
    And conversion "c-2" for "Acme" sells "5000.00" ZAR for "270.000000" USDT with a deadline 1 minute away
    And the sweeper voids conversion "c-3" for "Acme"
    Then the ledger counted these conversion postings:
      | outcome | code               | count |
      | posted  | none               | 1     |
      | refused | INSUFFICIENT_FUNDS | 1     |
      | voided  | none               | 1     |
