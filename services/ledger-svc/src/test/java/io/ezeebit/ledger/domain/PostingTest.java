package io.ezeebit.ledger.domain;

import static io.ezeebit.ledger.domain.Currencies.USDT;
import static io.ezeebit.ledger.domain.Currencies.ZAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import io.ezeebit.ledger.exception.ErrorCode;
import io.ezeebit.ledger.exception.LedgerException;
import org.junit.jupiter.api.Test;

class PostingTest {

	private final UUID merchant = UUID.randomUUID();
	private final UUID clearing = UUID.randomUUID();

	@Test
	void depositCreditsTheMerchantAndDebitsClearingByTheSameAmount() {
		Posting posting = Posting.deposit(merchant, clearing, Money.parse("100.00", ZAR));

		assertThat(posting.lines()).containsExactly(
				new PostingLine(merchant, Money.parse("100.00", ZAR)),
				new PostingLine(clearing, Money.parse("100.00", ZAR).negate()));
	}

	@Test
	void linesThatDoNotSumToZeroAreRejected() {
		List<PostingLine> lines = List.of(
				new PostingLine(merchant, Money.parse("100.00", ZAR)),
				new PostingLine(clearing, Money.parse("99.99", ZAR).negate()));

		assertThatThrownBy(() -> new Posting(lines))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.UNBALANCED_POSTING);
	}

	@Test
	void eachCurrencyMustSumToZeroOnItsOwn() {
		List<PostingLine> lines = List.of(
				new PostingLine(merchant, Money.parse("5.00", ZAR)),
				new PostingLine(clearing, Money.parse("5.000000", USDT).negate()));

		assertThatThrownBy(() -> new Posting(lines))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.UNBALANCED_POSTING);
	}

	@Test
	void twoCurrenciesEachBalancedArePermitted() {
		List<PostingLine> lines = List.of(
				new PostingLine(merchant, Money.parse("5.00", ZAR)),
				new PostingLine(clearing, Money.parse("5.00", ZAR).negate()),
				new PostingLine(merchant, Money.parse("1.000000", USDT)),
				new PostingLine(clearing, Money.parse("1.000000", USDT).negate()));

		assertThat(new Posting(lines).lines()).hasSize(4);
	}

	@Test
	void aSingleLineCannotBalance() {
		List<PostingLine> lines = List.of(new PostingLine(merchant, Money.parse("5.00", ZAR)));

		assertThatThrownBy(() -> new Posting(lines))
				.isInstanceOf(LedgerException.class)
				.extracting("code").isEqualTo(ErrorCode.UNBALANCED_POSTING);
	}

}
