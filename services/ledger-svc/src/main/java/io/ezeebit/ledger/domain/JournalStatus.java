package io.ezeebit.ledger.domain;

// VOID is a marker with no entries: it holds a conversion's identity so a late posting is refused.
public enum JournalStatus {
	POSTED, VOID
}
