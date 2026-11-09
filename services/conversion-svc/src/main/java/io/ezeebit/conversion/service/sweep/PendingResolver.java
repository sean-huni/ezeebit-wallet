package io.ezeebit.conversion.service.sweep;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingResolver {

	// The claimed row and what the sweep did with it: completed, voided, refused or unresolved.
	record Swept(UUID id, String action) {
	}

	Optional<Swept> resolveNext(List<UUID> skip);

}
