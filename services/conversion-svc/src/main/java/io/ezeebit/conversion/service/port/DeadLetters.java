package io.ezeebit.conversion.service.port;

// Record-and-alert only: nothing consumes these, the sweeper settles the conversion. Never throws, never blocks.
public interface DeadLetters {

	void record(DeadLetter letter);

}
