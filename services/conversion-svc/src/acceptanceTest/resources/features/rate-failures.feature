Feature: An unreliable rate service gives a clear refusal, never a guessed rate
  The exchange-rate service can be slow and can be wrong. A refusal costs the merchant one retry;
  a conversion at a wrong price is a serious incident. There is no cached or default rate.

  Background:
    Given a merchant "Acme"

  Scenario: A slow rate service is refused within the time limit
    Given "Acme" was quoted "1000.00" ZAR for USDT a moment ago
    And the rate service answers too slowly
    When "Acme" asks for quote "q2" to sell "1000.00" ZAR for USDT
    Then the request is refused with 503 RATE_UNAVAILABLE
    And the refusal came within 5 seconds
    And only 1 quote is stored for "Acme"

  Scenario: A rate service that answers late once is retried and the quote succeeds
    Given the rate service answers its first request after 3 seconds and then normally
    When "Acme" asks for quote "q1" to sell "100.00" ZAR for USDT
    Then quote "q1" is created
    And the rate service was asked 2 times

  Scenario: A failing rate service is refused
    Given the rate service fails with status 500
    When "Acme" asks for quote "q1" to sell "1000.00" ZAR for USDT
    Then the request is refused with 503 RATE_UNAVAILABLE
    And no quote is stored for "Acme"

  Scenario: A rate service error is not retried and is never dead-lettered
    Given the rate service fails with status 500
    When "Acme" asks for quote "q1" to sell "100.00" ZAR for USDT
    Then the request is refused with 503 RATE_UNAVAILABLE
    And the rate service was asked 1 time
    And no dead letter was recorded

  Scenario Outline: A bad rate is refused with its reason
    Given the rate service <answer>
    When "Acme" asks for quote "q1" to sell "1000.00" ZAR for USDT
    Then the request is refused with 502 RATE_REJECTED because <reason>
    And no quote is stored for "Acme"

    Examples:
      | answer                                       | reason            |
      | quotes ZAR in USDT at "0" now                | RATE_NOT_POSITIVE |
      | quotes ZAR in USDT at "-0.054254" now        | RATE_NOT_POSITIVE |
      | quotes ZAR in USDT at "0.054254" 60 seconds ago | RATE_STALE     |
      | quotes ZAR in USDT at "5.4254" now           | RATE_OUT_OF_BAND  |
      | answers with something that is not a rate    | RATE_MALFORMED    |

  Scenario: A sudden jump from the last quoted rate is refused
    Given "Acme" was quoted "100.000000" USDC for KES a moment ago
    And the rate service quotes USDC in KES at "150.00" now
    When "Acme" asks for quote "q2" to sell "100.000000" USDC for KES
    Then the request is refused with 502 RATE_REJECTED because RATE_JUMP
