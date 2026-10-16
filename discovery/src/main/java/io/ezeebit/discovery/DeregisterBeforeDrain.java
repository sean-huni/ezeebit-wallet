package io.ezeebit.discovery;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaServiceRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

// ContextClosedEvent is published before any Lifecycle bean stops, so the web server still serves while callers drop
// this instance. Registered through spring.factories: the shared resource folders already hold an AutoConfiguration.imports.
public class DeregisterBeforeDrain implements ApplicationListener<ContextClosedEvent> {

	static final String WAIT_PROPERTY = "ezeebit.discovery.deregister-wait";

	// The served config's 5s registry response cache plus the callers' 5s registry fetch.
	static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

	private static final Logger LOG = LoggerFactory.getLogger(DeregisterBeforeDrain.class);

	@FunctionalInterface
	public interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	private final Sleeper sleeper;

	public DeregisterBeforeDrain() {
		this(Thread::sleep);
	}

	DeregisterBeforeDrain(final Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	@Override
	public void onApplicationEvent(final ContextClosedEvent event) {
		ApplicationContext context = event.getApplicationContext();
		// Child contexts close during the root's bean destruction, after the drain: only the root deregisters.
		if (context.getParent() != null) {
			return;
		}
		EurekaServiceRegistry registry = context.getBeanProvider(EurekaServiceRegistry.class).getIfAvailable();
		EurekaRegistration registration = context.getBeanProvider(EurekaRegistration.class).getIfAvailable();
		if (registry == null || registration == null) {
			return;
		}
		String configured = context.getEnvironment().getProperty(WAIT_PROPERTY);
		Duration wait = configured == null ? DEFAULT_WAIT : DurationStyle.detectAndParse(configured);
		// Sets the instance DOWN. EurekaAutoServiceRegistration does the same on this event and again in its phase-0
		// stop: a status equal to the current one fires no update, so the repeats change nothing.
		registry.deregister(registration);
		LOG.info("Deregistered from Eureka; serving for {} ms more before the graceful drain", wait.toMillis());
		try {
			sleeper.sleep(wait);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
