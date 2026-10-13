package io.ezeebit.ledger.domain;

// VOID and REFUSED are markers with no entries: they hold a conversion's identity, so no posting lands later.
public enum JournalStatus {
	POSTED, VOID, REFUSED
}
