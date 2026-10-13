Feature: A deposit sent more than once is credited once
  The rail retries over unreliable connections. Its transfer reference is the Idempotency-Key,
  and the same key always means the same deposit.

  Background:
    Given a merchant "Acme"

  Scenario: A replayed deposit returns the original answer and credits once
    Given the rail has deposited "100.00" ZAR for "Acme" with key "t-1"
    When the rail sends the deposit with key "t-1" again
    Then the deposit is accepted
    And both answers are identical
    And exactly 1 deposit is recorded for "Acme" with key "t-1"
    And "Acme" holds "100.00" ZAR

  Scenario: The same key with a different deposit is refused
    Given the rail has deposited "100.00" ZAR for "Acme" with key "t-1"
    When the rail deposits "999.00" ZAR for "Acme" with key "t-1"
    Then the request is refused with 422 IDEMPOTENCY_KEY_REUSED
    And exactly 1 deposit is recorded for "Acme" with key "t-1"
    And "Acme" holds "100.00" ZAR

  Scenario: Two merchants may use the same key independently
    Given a merchant "Bistro"
    When the rail deposits "100.00" ZAR for "Acme" with key "t-1"
    And the rail deposits "40.00" ZAR for "Bistro" with key "t-1"
    Then the deposit is accepted
    And "Acme" holds "100.00" ZAR
    And "Bistro" holds "40.00" ZAR
