Feature: conversion-svc counts quotes, conversions and sweeps
  Operators read these on the Money row of Fleet Overview, and RateFeedRefusing alerts on the quote
  refusals. Every tag is bounded: a pair of known currencies, a code, never a merchant or an id.

  Background:
    Given a merchant "Acme" holding "1000.00" ZAR

  Scenario: Quotes are counted by pair, and refusals by code and the rate rule broken
    Given the rate service quotes ZAR in USDT at "0.054254" 60 seconds ago
    When "Acme" asks for quote "q1" to sell "1000.00" ZAR for USDT
    And "Acme" asks for quote "q2" to sell "100.00" KES for USDT
    And "Acme" asks for quote "q3" to sell "1.00" XYZ for USDT
    Then conversion-svc counted these quotes:
      | pair     | outcome | reason           | detail     | count |
      | ZAR/USDT | refused | RATE_REJECTED    | RATE_STALE | 1     |
      | KES/USDT | issued  | none             | none       | 1     |
      | unknown  | refused | UNKNOWN_CURRENCY | none       | 1     |
    And conversion-svc timed 2 rate-service calls that answered

  Scenario: A rate service that fails is counted as unavailable
    Given the rate service fails with status 500
    When "Acme" asks for quote "q1" to sell "1000.00" ZAR for USDT
    Then conversion-svc counted these quotes:
      | pair     | outcome | reason           | detail | count |
      | ZAR/USDT | refused | RATE_UNAVAILABLE | none   | 1     |
    And conversion-svc timed 1 rate-service call that was unavailable

  Scenario: Each conversion is counted once, by its outcome
    Given "Acme" has quote "q1" to sell "400.00" ZAR for USDT
    And "Acme" has quote "q2" to buy "100.000000" USDT with ZAR
    When "Acme" accepts quote "q1" with key "k1"
    And "Acme" accepts quote "q1" with key "k1"
    And "Acme" accepts quote "q2" with key "k2"
    Then conversion-svc counted these conversions:
      | status    | reason             | count |
      | COMPLETED | none               | 1     |
      | REJECTED  | INSUFFICIENT_FUNDS | 1     |

  Scenario: A conversion left PENDING is counted, shows as overdue, and the sweeper's action is counted
    Given "Acme" has quote "q1" to sell "100.00" ZAR for USDT
    And the ledger does not answer in time
    When "Acme" accepts quote "q1" with key "k1"
    Then the conversion is PENDING
    And conversion-svc reports 0 more overdue PENDING conversions than at the start
    When the posting deadline of the conversion has passed
    Then conversion-svc reports 1 more overdue PENDING conversion than at the start
    When the sweeper runs
    Then conversion-svc counted these conversions:
      | status   | reason | count |
      | PENDING  | none   | 1     |
      | REJECTED | VOIDED | 1     |
    And conversion-svc counted these sweeps:
      | action | count |
      | voided | 1     |
    And conversion-svc reports 0 more overdue PENDING conversions than at the start
