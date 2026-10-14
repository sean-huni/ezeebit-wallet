Feature: A ledger that does not answer leaves the conversion PENDING, never guessed
  If the ledger's answer does not arrive, the money may or may not have moved. The conversion stays
  PENDING until its posting deadline has passed, then the sweeper settles it to exactly one outcome.

  Background:
    Given a merchant "Acme" holding "1000.00" ZAR
    And "Acme" has quote "q1" to sell "100.00" ZAR for USDT

  Scenario: The posting never reached the ledger: the sweeper voids it, and a late posting is refused
    Given the ledger does not answer in time
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is PENDING
    When the posting deadline of the conversion has passed
    And the sweeper runs
    Then the conversion shows status REJECTED because VOIDED
    When the lost posting finally reaches the ledger
    Then the request is refused with 409 CONVERSION_VOIDED
    And the ledger holds exactly 0 conversions for "Acme"
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |

  Scenario: The ledger posted but its answer was lost: the sweeper finds it, and the money moved once
    Given the ledger posts conversions but its answers are lost
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is PENDING
    When the posting deadline of the conversion has passed
    And the sweeper runs
    Then the conversion shows status COMPLETED
    And the ledger holds exactly 1 conversion for "Acme"
    And "Acme" holds "900.00" ZAR
    And the ledger's books balance

  Scenario: A retry while the quote is live posts again safely and completes once
    Given the ledger does not answer in time
    And "Acme" has accepted quote "q1" with key "k1" and got PENDING
    And the ledger answers normally again
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is COMPLETED
    And the ledger holds exactly 1 conversion for "Acme"
    And "Acme" holds "900.00" ZAR

  Scenario: A retry after the quote expired returns the current state and never posts at a stale rate
    Given the ledger does not answer in time
    And "Acme" has accepted quote "q1" with key "k1" and got PENDING
    And the ledger answers normally again
    And quote "q1" has expired
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is PENDING
    And the ledger was asked to post 1 conversion
    And the ledger holds exactly 0 conversions for "Acme"

  Scenario: A refused conversion stays refused when money arrives during a same-key retry
    Given "Acme" has quote "big" to buy "100.000000" USDT with ZAR
    And the ledger's first answer takes 500 ms and its second 700 ms
    When "Acme" accepts quote "big" with key "k1", and while that answer is on its way "10000.00" ZAR arrives and "Acme" sends the same request again
    Then both answers are refused with 422 INSUFFICIENT_FUNDS
    And the conversion and the ledger agree
    And the ledger holds exactly 0 conversions for "Acme"
    And "Acme" holds "11000.00" ZAR
    Given the ledger answers normally again
    And "Acme" has quote "fresh" to buy "100.000000" USDT with ZAR
    When "Acme" accepts quote "fresh" with key "k2"
    Then the conversion is COMPLETED
    And the ledger holds exactly 1 conversion for "Acme"
    And the ledger's books balance

  Scenario: A ledger refusal of the posting's content is final
    Given the ledger refuses every posting with 400 AMOUNT_TOO_LARGE
    When "Acme" accepts quote "q1" with key "k1"
    Then the request is refused with 409 CONVERSION_REJECTED because INPUT_REFUSED
    And the conversion shows status REJECTED because INPUT_REFUSED

  Scenario: A ledger that says the conversion id is taken leaves the outcome to the sweeper
    Given the ledger refuses every posting with 422 IDEMPOTENCY_KEY_REUSED
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is PENDING
    When the posting deadline of the conversion has passed
    And the sweeper runs
    Then the conversion shows status REJECTED because VOIDED
    And the ledger holds exactly 0 conversions for "Acme"

  Scenario: Two sweepers running at once settle each conversion exactly once
    Given the ledger does not answer in time
    And "Acme" has 4 PENDING conversions of "10.00" ZAR for USDT
    And the ledger answers normally again
    And the posting deadline of every conversion has passed
    When two sweepers run at the same time
    Then every one of those conversions shows status REJECTED because VOIDED
    And the ledger was asked to void each of them exactly once
