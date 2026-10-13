Feature: A merchant is quoted a firm price
  The rate comes from the exchange-rate service. The platform keeps a spread, and every rounding
  favours the platform by at most one minor unit. A quote lives for a short time.

  Background:
    Given a merchant "Acme"

  Scenario: Selling a fixed amount: the amount received rounds down
    When "Acme" asks for quote "q1" to sell "1000.00" ZAR for USDT
    Then quote "q1" is offered:
      | sellCurrency | sellAmount | buyCurrency | buyAmount | rate     | appliedRate | spreadBps |
      | ZAR          | 1000.00    | USDT        | 53.982730 | 0.054254 | 0.05398273  | 50        |
    And quote "q1" expires 15 seconds after it was made

  Scenario: Buying a fixed amount: the amount paid rounds up
    When "Acme" asks for quote "q1" to buy "1000.00" ZAR with USDT
    Then quote "q1" is offered:
      | sellCurrency | sellAmount | buyCurrency | buyAmount | rate    | appliedRate | spreadBps |
      | USDT         | 54.525807  | ZAR         | 1000.00   | 18.4321 | 18.3399395  | 50        |

  Scenario: The same currency on both sides is refused
    When "Acme" asks for quote "q1" to sell "10.00" ZAR for ZAR
    Then the request is refused with 400 SAME_CURRENCY

  Scenario: A pair the platform does not trade is refused
    When "Acme" asks for quote "q1" to sell "10.00" ZAR for NGN
    Then the request is refused with 400 UNSUPPORTED_PAIR

  Scenario: An unknown currency is refused
    When "Acme" asks for quote "q1" to sell "10.00" EUR for USDT
    Then the request is refused with 400 UNKNOWN_CURRENCY

  Scenario: An amount with more decimals than its currency allows is refused, never rounded
    When "Acme" asks for quote "q1" to sell "10.001" ZAR for USDT
    Then the request is refused with 400 AMOUNT_SCALE_EXCEEDED

  Scenario: A quote fixes exactly one side
    When "Acme" asks for a quote to sell ZAR for USDT naming both amounts
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "sellAmount"

  Scenario: An amount too small to buy anything is refused
    When "Acme" asks for quote "q1" to sell "0.000001" USDT for ZAR
    Then the request is refused with 400 AMOUNT_TOO_SMALL
