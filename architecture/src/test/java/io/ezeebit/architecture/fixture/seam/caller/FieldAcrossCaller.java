package io.ezeebit.architecture.fixture.seam.caller;

import io.ezeebit.architecture.fixture.seam.callee.ConcreteCallee;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FieldAcrossCaller {

	@Autowired
	private ConcreteCallee callee;

}
