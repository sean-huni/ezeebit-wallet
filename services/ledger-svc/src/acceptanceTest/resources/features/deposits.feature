Feature: Money arrives and the balance goes up
  A merchant holds balances in several currencies at once. A deposit from the money-in rail
  raises the balance in its own currency, and two currencies are never mixed.

  Background:
    Given a merchant "Acme"

  Scenario: A deposit raises the balance in its currency
    When the rail deposits "1500.00" ZAR for "Acme" with key "t-1"
    Then the deposit is accepted with a balance after of "1500.00"
    And "Acme" holds "1500.00" ZAR

  Scenario: Fiat and stablecoin balances are held side by side and never mixed
    Given the rail has deposited "100.00" ZAR for "Acme" with key "t-1"
    And the rail has deposited "25.123456" USDT for "Acme" with key "t-2"
    Then "Acme" holds exactly:
      | currency | balance   |
      | USDT     | 25.123456 |
      | ZAR      | 100.00    |
    When "Acme" reads its ZAR statement
    Then the statement shows:
      | currency | amount | balance after | reference      |
      | ZAR      | 100.00 | 100.00        | transfer t-1   |

  Scenario Outline: An amount with more decimals than its currency allows is rejected, never rounded
    When the rail deposits "<amount>" <currency> for "Acme" with key "t-1"
    Then the request is refused with 400 AMOUNT_SCALE_EXCEEDED
    And "Acme" holds nothing

    Examples:
      | amount    | currency |
      | 10.001    | ZAR      |
      | 0.005     | NGN      |
      | 1.0000001 | USDT     |

  Scenario: The refusal explains itself
    When the rail deposits "10.001" ZAR for "Acme" with key "t-1"
    Then the problem detail reads "Amount 10.001 has more decimals than ZAR allows (2)."

  Scenario Outline: A deposit that is not positive is rejected
    When the rail deposits "<amount>" ZAR for "Acme" with key "t-1"
    Then the request is refused with 400 AMOUNT_NOT_POSITIVE
    And "Acme" holds nothing

    Examples:
      | amount |
      | 0      |
      | 0.00   |
      | -5.00  |

  Scenario Outline: An amount that is not a plain decimal string is rejected
    When the rail deposits "<amount>" ZAR for "Acme" with key "t-1"
    Then the request is refused with 400 VALIDATION_FAILED
    And "Acme" holds nothing

    Examples:
      | amount   |
      | 1e3      |
      | 1,000.00 |
      | ten      |

  Scenario: An unsupported currency is rejected
    When the rail deposits "10.00" EUR for "Acme" with key "t-1"
    Then the request is refused with 400 UNKNOWN_CURRENCY
    And "Acme" holds nothing

  Scenario: A deposit without an idempotency key is rejected
    When the rail deposits "10.00" ZAR for "Acme" without an idempotency key
    Then the request is refused with 400 IDEMPOTENCY_KEY_MISSING
    And "Acme" holds nothing

  Scenario: A body that is not JSON is rejected
    When the rail sends the body "not json" with key "t-1"
    Then the request is refused with 400 MALFORMED_REQUEST

  Scenario: An amount with more digits than the ledger can store is rejected
    When the rail deposits "123456789012345678901" ZAR for "Acme" with key "t-1"
    Then the request is refused with 400 AMOUNT_TOO_LARGE
    And "Acme" holds nothing

  Scenario: A deposit that would take the balance past what the ledger can store is refused
    Given the rail has deposited "99999999999999999999.99" ZAR for "Acme" with key "o1"
    When the rail deposits "99999999999999999999.99" ZAR for "Acme" with key "o2"
    Then the request is refused with 422 BALANCE_LIMIT_EXCEEDED
    And exactly 0 deposits are recorded for "Acme" with key "o2"
    And "Acme" holds "99999999999999999999.99" ZAR
    And every journal sums to zero in each currency

  Scenario: A reference containing a control character is rejected
    When the rail deposits "10.00" ZAR for "Acme" with key "t-1" and a reference containing a NUL character
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "reference"
    And "Acme" holds nothing

  Scenario: An idempotency key containing a control character is rejected
    When the rail deposits "10.00" ZAR for "Acme" with a key containing a tab
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "Idempotency-Key"
    And "Acme" holds nothing

  Scenario: An amount sent as a JSON number is rejected, only a decimal string is accepted
    When the rail sends an amount of 10.00 as a JSON number for "Acme" with key "t-1"
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "amount"
    And "Acme" holds nothing

  Scenario Outline: A deposit without a usable merchant id names the field
    When the rail sends a deposit whose merchantId is "<merchantId>" with key "t-1"
    Then the request is refused with 400 VALIDATION_FAILED
    And the problem names the field "merchantId"

    Examples:
      | merchantId |
      | null       |
      | not-a-uuid |
