Feature: The statement explains exactly how a balance reached its value
  Every entry carries the balance it left behind, so any balance can be explained at any later date.

  Background:
    Given a merchant "Acme"

  Scenario: Each entry shows the balance after it
    Given the rail has deposited "100.00" ZAR for "Acme" with key "t-1"
    And the rail has deposited "250.50" ZAR for "Acme" with key "t-2"
    And the rail has deposited "0.01" ZAR for "Acme" with key "t-3"
    When "Acme" reads its ZAR statement
    Then the statement shows:
      | currency | amount | balance after | reference    |
      | ZAR      | 100.00 | 100.00        | transfer t-1 |
      | ZAR      | 250.50 | 350.50        | transfer t-2 |
      | ZAR      | 0.01   | 350.51        | transfer t-3 |
    And "Acme" holds "350.51" ZAR
    And the ZAR balance of "Acme" equals the sum of its statement

  Scenario: A long statement is read page by page without gaps or repeats
    Given the rail has deposited "1.00" KES for "Acme" with key "t-1"
    And the rail has deposited "2.00" KES for "Acme" with key "t-2"
    And the rail has deposited "3.00" KES for "Acme" with key "t-3"
    When "Acme" reads its KES statement 2 entries at a time
    Then the pages together show:
      | currency | amount | balance after | reference    |
      | KES      | 1.00   | 1.00          | transfer t-1 |
      | KES      | 2.00   | 3.00          | transfer t-2 |
      | KES      | 3.00   | 6.00          | transfer t-3 |

  Scenario: A window that ends before any deposit shows nothing
    Given the rail has deposited "100.00" ZAR for "Acme" with key "t-1"
    When "Acme" reads its ZAR statement up to "2000-01-01T00:00:00Z"
    Then the statement is empty

  Scenario: A merchant that never received money has an empty statement
    When "Acme" reads its statement in every currency
    Then the statement is empty

  Scenario: A statement in an unsupported currency is refused
    When "Acme" reads its EUR statement
    Then the request is refused with 400 UNKNOWN_CURRENCY

  Scenario: A merchant id that is not a UUID is refused
    When anyone reads the balances of merchant id "not-a-uuid"
    Then the request is refused with 400 VALIDATION_FAILED

  Scenario: An API version that does not exist is refused
    When anyone reads the balances of "Acme" through API version "v2"
    Then the request is refused with 400 UNSUPPORTED_API_VERSION
