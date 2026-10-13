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

  Scenario: The window includes a deposit made exactly at its start and excludes one made exactly at its end
    Given the rail has deposited "1.00" ZAR for "Acme" with key "t-1"
    And the rail has deposited "2.00" ZAR for "Acme" with key "t-2"
    And the rail has deposited "4.00" ZAR for "Acme" with key "t-3"
    When "Acme" reads its ZAR statement from the time of deposit "t-1" up to the time of deposit "t-2"
    Then the statement shows:
      | currency | amount | balance after | reference    |
      | ZAR      | 1.00   | 1.00          | transfer t-1 |
    When "Acme" reads its ZAR statement from the time of deposit "t-2" up to the time of deposit "t-3"
    Then the statement shows:
      | currency | amount | balance after | reference    |
      | ZAR      | 2.00   | 3.00          | transfer t-2 |

  Scenario: A statement in every currency shows each entry with its own account's balance after
    Given the rail has deposited "100.00" ZAR for "Acme" with key "t-1"
    And the rail has deposited "5.5" USDT for "Acme" with key "t-2"
    And the rail has deposited "50.00" ZAR for "Acme" with key "t-3"
    When "Acme" reads its statement in every currency
    Then the statement shows:
      | currency | amount   | balance after | reference    |
      | ZAR      | 100.00   | 100.00        | transfer t-1 |
      | USDT     | 5.500000 | 5.500000      | transfer t-2 |
      | ZAR      | 50.00    | 150.00        | transfer t-3 |
    And every journal sums to zero in each currency
    And every merchant balance equals the sum of its entries

  Scenario Outline: A window bound outside the years 1970 to 9999 is refused
    When "Acme" reads its ZAR statement <bound> "<instant>"
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "<field>"

    Examples:
      | bound | instant                     | field |
      | up to | +999999999-12-31T23:59:59Z  | to    |
      | up to | +10000-01-01T00:00:00Z      | to    |
      | from  | 1969-12-31T23:59:59Z        | from  |

  Scenario: A window whose start is not before its end is refused
    When "Acme" reads its ZAR statement from "2026-01-02T00:00:00Z" up to "2026-01-01T00:00:00Z"
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "to"
