package io.ezeebit.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaServiceRegistry;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class DeregisterBeforeDrainTest {

	private final List<String> events = new CopyOnWriteArrayList<>();

	private final EurekaServiceRegistry registry = mock(EurekaServiceRegistry.class);

	private final EurekaRegistration registration = mock(EurekaRegistration.class);

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	void deregistersAndWaitsBeforeAnyLifecycleBeanStops() {
		doAnswer(call -> events.add("deregister")).when(registry).deregister(any());
		GenericApplicationContext context = context(Map.of("ezeebit.discovery.deregister-wait", "7s"));
		context.registerBean("webServer", SmartLifecycle.class, () -> new RecordingLifecycle(events));
		context.addApplicationListener(new DeregisterBeforeDrain(wait -> events.add("wait " + wait)));
		context.refresh();

		context.close();

		assertThat(events).containsExactly("deregister", "wait PT7S", "web server stops");
	}

	@Test
	void waitsTenSecondsWhenNothingIsConfigured() {
		GenericApplicationContext context = context(Map.of());
		context.refresh();

		new DeregisterBeforeDrain(wait -> events.add("wait " + wait)).onApplicationEvent(new ContextClosedEvent(context));

		verify(registry).deregister(registration);
		assertThat(events).containsExactly("wait PT10S");
	}

	@Test
	void aChildContextClosingDoesNothing() {
		GenericApplicationContext parent = context(Map.of());
		parent.refresh();
		GenericApplicationContext child = context(Map.of());
		child.setParent(parent);
		child.refresh();

		new DeregisterBeforeDrain(wait -> events.add("wait " + wait)).onApplicationEvent(new ContextClosedEvent(child));

		verify(registry, never()).deregister(any());
		assertThat(events).isEmpty();
	}

	@Test
	void aServiceThatNeverRegisteredClosesWithoutWaiting() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();

		new DeregisterBeforeDrain(wait -> events.add("wait " + wait)).onApplicationEvent(new ContextClosedEvent(context));

		assertThat(events).isEmpty();
	}

	@Test
	void anInterruptedWaitEndsAndKeepsTheInterrupt() {
		GenericApplicationContext context = context(Map.of());
		context.refresh();

		new DeregisterBeforeDrain(wait -> {
			throw new InterruptedException("stop");
		}).onApplicationEvent(new ContextClosedEvent(context));

		verify(registry).deregister(registration);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	private GenericApplicationContext context(final Map<String, Object> properties) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		context.registerBean(EurekaServiceRegistry.class, () -> registry);
		context.registerBean(EurekaRegistration.class, () -> registration);
		return context;
	}

	private record RecordingLifecycle(List<String> events) implements SmartLifecycle {

		@Override
		public void start() {
			// Started by the context; only stop is recorded.
		}

		@Override
		public void stop() {
			events.add("web server stops");
		}

		@Override
		public boolean isRunning() {
			return true;
		}
	}
}
