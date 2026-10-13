Feature: The ledger posts a conversion in one transaction
  conversion-svc asks the ledger to move money from one of a merchant's currencies into another.
  The ledger alone decides whether the merchant holds enough, and posts all four entries or none.

  Background:
    Given a merchant "Acme"
    And the rail has deposited "1000.00" ZAR for "Acme" with key "t-1"

  Scenario: A conversion posts four entries that net to zero in each currency
    When conversion "c-1" for "Acme" sells "184.32" ZAR for "10.000000" USDT with a deadline 1 minute away
    Then the posting is accepted with status POSTED
    And "Acme" holds exactly:
      | currency | balance   |
      | USDT     | 10.000000 |
      | ZAR      | 815.68    |
    And conversion "c-1" for "Acme" has the entries:
      | account     | currency | amount     |
      | MERCHANT    | ZAR      | -184.32    |
      | PLATFORM_FX | ZAR      | 184.32     |
      | PLATFORM_FX | USDT     | -10.000000 |
      | MERCHANT    | USDT     | 10.000000  |
    And every journal sums to zero in each currency
    And every merchant balance equals the sum of its entries

  Scenario: A merchant cannot convert more than it holds, and the refusal is recorded
    When conversion "c-1" for "Acme" sells "1000.01" ZAR for "54.000000" USDT with a deadline 1 minute away
    Then the request is refused with 422 INSUFFICIENT_FUNDS
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |
    And conversion "c-1" of "Acme" is recorded as REFUSED with no entries
    And looking up conversion "c-1" for "Acme" shows status REFUSED because INSUFFICIENT_FUNDS

  Scenario: A refusal is final: money arriving later does not let the same conversion post
    When conversion "c-1" for "Acme" sells "1500.00" ZAR for "81.000000" USDT with a deadline 1 minute away
    Then the request is refused with 422 INSUFFICIENT_FUNDS
    Given the rail has deposited "1000.00" ZAR for "Acme" with key "t-2"
    When conversion "c-1" is sent again
    Then the request is refused with 422 INSUFFICIENT_FUNDS
    And conversion "c-1" of "Acme" is recorded as REFUSED with no entries
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 2000.00 |

  Scenario: A refused conversion id sent with a different body is a reused key, not a replayed refusal
    When conversion "c-1" for "Acme" sells "1500.00" ZAR for "81.000000" USDT with a deadline 1 minute away
    Then the request is refused with 422 INSUFFICIENT_FUNDS
    When conversion "c-1" for "Acme" sells "1400.00" ZAR for "75.000000" USDT with a deadline 1 minute away
    Then the request is refused with 422 IDEMPOTENCY_KEY_REUSED
    And conversion "c-1" of "Acme" is recorded as REFUSED with no entries
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |

  Scenario: Voiding a refused conversion answers its refusal
    When conversion "c-1" for "Acme" sells "1500.00" ZAR for "81.000000" USDT with a deadline 1 minute away
    And the sweeper voids conversion "c-1" for "Acme"
    Then the posting is accepted with status REFUSED
    And conversion "c-1" of "Acme" is recorded as REFUSED with no entries

  Scenario: A merchant cannot sell a currency it never held
    When conversion "c-1" for "Acme" sells "1.000000" USDC for "18.00" ZAR with a deadline 1 minute away
    Then the request is refused with 422 INSUFFICIENT_FUNDS
    And conversion "c-1" of "Acme" is recorded as REFUSED with no entries

  Scenario: A posting that arrives after its deadline is refused and moves nothing
    When conversion "c-1" for "Acme" sells "184.32" ZAR for "10.000000" USDT with a deadline 1 minute ago
    Then the request is refused with 409 POSTING_DEADLINE_PASSED
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |
    And exactly 0 postings are recorded for conversion "c-1" of "Acme"

  Scenario: A void blocks a posting that arrives later
    When the sweeper voids conversion "c-1" for "Acme"
    Then the posting is accepted with status VOID
    When conversion "c-1" for "Acme" sells "184.32" ZAR for "10.000000" USDT with a deadline 1 minute away
    Then the request is refused with 409 CONVERSION_VOIDED
    And "Acme" holds exactly:
      | currency | balance |
      | ZAR      | 1000.00 |
    And looking up conversion "c-1" for "Acme" shows status VOID

  Scenario: A posted conversion cannot be voided
    Given conversion "c-1" for "Acme" has sold "184.32" ZAR for "10.000000" USDT
    When the sweeper voids conversion "c-1" for "Acme"
    Then the request is refused with 409 CONVERSION_ALREADY_POSTED
    And looking up conversion "c-1" for "Acme" shows status POSTED

  Scenario: Voiding twice gives the same answer
    When the sweeper voids conversion "c-1" for "Acme"
    And the sweeper voids conversion "c-1" for "Acme"
    Then both answers are identical
    And exactly 1 posting is recorded for conversion "c-1" of "Acme"

  Scenario: A replayed posting returns the original answer and moves the money once
    Given conversion "c-1" for "Acme" has sold "184.32" ZAR for "10.000000" USDT
    When conversion "c-1" is sent again
    Then the posting is accepted with status POSTED
    And both answers are identical
    And exactly 1 posting is recorded for conversion "c-1" of "Acme"
    And "Acme" holds "815.68" ZAR

  Scenario: The same conversion id with different amounts is refused
    Given conversion "c-1" for "Acme" has sold "184.32" ZAR for "10.000000" USDT
    When conversion "c-1" for "Acme" sells "200.00" ZAR for "10.000000" USDT with a deadline 1 minute away
    Then the request is refused with 422 IDEMPOTENCY_KEY_REUSED
    And "Acme" holds "815.68" ZAR

  Scenario: The sweeper can ask whether a conversion was posted
    Given conversion "c-1" for "Acme" has sold "184.32" ZAR for "10.000000" USDT
    Then looking up conversion "c-1" for "Acme" shows status POSTED
    And looking up conversion "c-2" for "Acme" is refused with 404 POSTING_NOT_FOUND

  Scenario: A path the ledger does not serve is not a missing posting
    When the path "/internal/v1/conversion-posting/c-1" is requested
    Then the request is refused with 404 NOT_FOUND

  Scenario: A conversion between one currency and itself is refused
    When conversion "c-1" for "Acme" sells "10.00" ZAR for "10.00" ZAR with a deadline 1 minute away
    Then the request is refused with 400 SAME_CURRENCY

  Scenario: A lookup without the merchant id names the missing parameter
    When conversion "c-1" is looked up without a merchant id
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "merchantId"

  Scenario: Concurrent conversions never spend more than the balance
    Each conversion buys a different currency, so only the lock on the ZAR account stands between them.
    When 8 conversions for "Acme" each sell "300.00" ZAR at the same time, buying in turn:
      | currency | amount    |
      | USDT     | 16.000000 |
      | USDC     | 16.000000 |
      | NGN      | 450000.00 |
      | KES      | 2100.00   |
    Then exactly 3 postings are accepted and the rest are refused with 422 INSUFFICIENT_FUNDS
    And "Acme" holds "100.00" ZAR
    And every journal sums to zero in each currency
    And every merchant balance equals the sum of its entries
