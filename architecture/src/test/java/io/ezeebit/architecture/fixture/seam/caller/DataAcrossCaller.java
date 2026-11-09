package io.ezeebit.architecture.fixture.seam.caller;

import io.ezeebit.architecture.fixture.seam.callee.CalleeKind;
import io.ezeebit.architecture.fixture.seam.callee.CalleeRecord;
import org.springframework.stereotype.Component;

@Component
public class DataAcrossCaller {

	public DataAcrossCaller(final CalleeRecord value, final CalleeKind kind) {
	}

}
