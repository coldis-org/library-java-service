package org.coldis.library.test.service.jms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.coldis.library.service.jms.JmsListenerBulkhead;
import org.coldis.library.service.jms.JmsListenerBulkheadException;
import org.coldis.library.service.jms.JmsListenerBulkheadPermit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JmsListenerBulkhead} on its own: limit, arrival order, timeouts,
 * container stop, interruption, reserved permits and live limits. Threads hold
 * their permits until the test releases them; every wait is on a latch or on
 * the bulkhead state, never a fixed sleep.
 */
@DisplayName("JMS listener bulkhead")
public class JmsListenerBulkheadTest {

	/**
	 * Longest wait for a state the test expects.
	 */
	private static final Duration WAIT = Duration.ofSeconds(10);

	/**
	 * Destination label.
	 */
	private static final String DESTINATION = "bulkhead/unit";

	/**
	 * A thread that acquires a permit and holds it until released.
	 */
	private static final class PermitHolder {

		/**
		 * Name (recorded in the grant order).
		 */
		private final String name;

		/**
		 * Counted down when the permit is granted.
		 */
		private final CountDownLatch granted = new CountDownLatch(1);

		/**
		 * Counted down by the test to release the permit.
		 */
		private final CountDownLatch release = new CountDownLatch(1);

		/**
		 * Counted down once the permit is released or the wait failed.
		 */
		private final CountDownLatch finished = new CountDownLatch(1);

		/**
		 * Aborted wait, if any.
		 */
		private final AtomicReference<JmsListenerBulkheadException> failure = new AtomicReference<>();

		/**
		 * If the interrupt flag was set right after the wait was aborted.
		 */
		private final AtomicBoolean interruptedAfterFailure = new AtomicBoolean();

		/**
		 * Thread.
		 */
		private Thread thread;

		/**
		 * Constructor.
		 *
		 * @param name Name.
		 */
		private PermitHolder(final String name) {
			this.name = name;
		}

		/**
		 * Releases the permit and waits until it is released.
		 *
		 * @throws InterruptedException If interrupted.
		 */
		private void releaseAndWait() throws InterruptedException {
			this.release.countDown();
			Assertions.assertTrue(this.finished.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS), this.name + " did not finish");
		}

	}

	/**
	 * Tracks the handlers holding a permit and the most seen at once.
	 */
	private static final class InFlightProbe {

		/**
		 * Current.
		 */
		private final AtomicInteger current = new AtomicInteger();

		/**
		 * Maximum.
		 */
		private final AtomicInteger maximum = new AtomicInteger();

		/**
		 * Enters a handler.
		 */
		private void enter() {
			this.maximum.accumulateAndGet(this.current.incrementAndGet(), Math::max);
		}

		/**
		 * Exits a handler.
		 */
		private void exit() {
			this.current.decrementAndGet();
		}

	}

	/**
	 * Holders started by the current test (released after it).
	 */
	private final List<PermitHolder> holders = new CopyOnWriteArrayList<>();

	/**
	 * Releases every holder left by a test.
	 *
	 * @throws InterruptedException If interrupted.
	 */
	@AfterEach
	public void releaseHolders() throws InterruptedException {
		for (final PermitHolder holder : this.holders) {
			holder.release.countDown();
			holder.thread.interrupt();
			holder.thread.join(JmsListenerBulkheadTest.WAIT.toMillis());
		}
		this.holders.clear();
	}

	/**
	 * Waits until a condition holds, then asserts it.
	 *
	 * @param condition   Condition.
	 * @param description Description.
	 */
	private static void waitUntil(
			final BooleanSupplier condition,
			final String description) {
		final long deadline = System.nanoTime() + JmsListenerBulkheadTest.WAIT.toNanos();
		while (!condition.getAsBoolean() && (System.nanoTime() < deadline)) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		}
		Assertions.assertTrue(condition.getAsBoolean(), description);
	}

	/**
	 * Starts a holder.
	 *
	 * @param  threadBuilder Thread builder.
	 * @param  bulkhead      Bulkhead.
	 * @param  name          Name.
	 * @param  reservation   Reservation.
	 * @param  maxWait       Maximum wait.
	 * @param  stopped       If the container stopped.
	 * @param  grantOrder    Grant order (names).
	 * @param  probe         In-flight probe.
	 * @return               The holder.
	 */
	private PermitHolder startHolder(
			final Thread.Builder threadBuilder,
			final JmsListenerBulkhead bulkhead,
			final String name,
			final String reservation,
			final Duration maxWait,
			final BooleanSupplier stopped,
			final List<String> grantOrder,
			final InFlightProbe probe) {
		final PermitHolder holder = new PermitHolder(name);
		holder.thread = threadBuilder.name("bulkhead-test-" + name).unstarted(() -> {
			try (JmsListenerBulkheadPermit permit = bulkhead.acquire(reservation, JmsListenerBulkheadTest.DESTINATION, maxWait, stopped)) {
				grantOrder.add(name);
				probe.enter();
				holder.granted.countDown();
				holder.release.await();
				probe.exit();
			}
			catch (final JmsListenerBulkheadException exception) {
				holder.interruptedAfterFailure.set(Thread.currentThread().isInterrupted());
				holder.failure.set(exception);
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			finally {
				holder.finished.countDown();
			}
		});
		this.holders.add(holder);
		holder.thread.start();
		return holder;
	}

	/**
	 * Starts an unreserved holder on a platform thread, without timeout.
	 *
	 * @param  bulkhead   Bulkhead.
	 * @param  name       Name.
	 * @param  grantOrder Grant order.
	 * @param  probe      In-flight probe.
	 * @return            The holder.
	 */
	private PermitHolder startHolder(
			final JmsListenerBulkhead bulkhead,
			final String name,
			final List<String> grantOrder,
			final InFlightProbe probe) {
		return this.startHolder(Thread.ofPlatform(), bulkhead, name, null, null, () -> false, grantOrder, probe);
	}

	/**
	 * Starts a reserved holder on a platform thread, without timeout.
	 *
	 * @param  bulkhead    Bulkhead.
	 * @param  name        Name.
	 * @param  reservation Reservation.
	 * @param  grantOrder  Grant order.
	 * @param  probe       In-flight probe.
	 * @return             The holder.
	 */
	private PermitHolder startReservedHolder(
			final JmsListenerBulkhead bulkhead,
			final String name,
			final String reservation,
			final List<String> grantOrder,
			final InFlightProbe probe) {
		return this.startHolder(Thread.ofPlatform(), bulkhead, name, reservation, null, () -> false, grantOrder, probe);
	}

	/**
	 * Starts a holder and waits until it is granted.
	 *
	 * @param  bulkhead   Bulkhead.
	 * @param  name       Name.
	 * @param  grantOrder Grant order.
	 * @param  probe      In-flight probe.
	 * @return            The holder.
	 */
	private PermitHolder startGrantedHolder(
			final JmsListenerBulkhead bulkhead,
			final String name,
			final List<String> grantOrder,
			final InFlightProbe probe) throws InterruptedException {
		final PermitHolder holder = this.startHolder(bulkhead, name, grantOrder, probe);
		Assertions.assertTrue(holder.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS), name + " was not granted");
		return holder;
	}

	/**
	 * Starts a holder and waits until it is queued.
	 *
	 * @param  bulkhead   Bulkhead.
	 * @param  name       Name.
	 * @param  grantOrder Grant order.
	 * @param  probe      In-flight probe.
	 * @return            The holder.
	 */
	private PermitHolder startQueuedHolder(
			final JmsListenerBulkhead bulkhead,
			final String name,
			final List<String> grantOrder,
			final InFlightProbe probe) {
		final int waitingBefore = bulkhead.getWaiting();
		final PermitHolder holder = this.startHolder(bulkhead, name, grantOrder, probe);
		JmsListenerBulkheadTest.waitUntil(() -> bulkhead.getWaiting() == (waitingBefore + 1), name + " was not queued");
		return holder;
	}

	/**
	 * Runs the limit scenario: 20 handlers, 3 permits.
	 *
	 * @param  threadBuilder        Thread builder.
	 * @throws InterruptedException If interrupted.
	 */
	private void assertLimit(
			final Thread.Builder threadBuilder) throws InterruptedException {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("limit", () -> 3, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final List<PermitHolder> startedHolders = new ArrayList<>();
		for (int holderIndex = 0; holderIndex < 20; holderIndex++) {
			startedHolders.add(this.startHolder(threadBuilder, bulkhead, "holder-" + holderIndex, null, null, () -> false, grantOrder, probe));
		}
		JmsListenerBulkheadTest.waitUntil(() -> (bulkhead.getInFlight() == 3) && (bulkhead.getWaiting() == 17), "3 in flight and 17 waiting");
		Assertions.assertEquals(3, probe.maximum.get());
		for (final PermitHolder holder : startedHolders) {
			holder.release.countDown();
		}
		for (final PermitHolder holder : startedHolders) {
			holder.releaseAndWait();
			Assertions.assertNull(holder.failure.get());
		}
		Assertions.assertEquals(20, grantOrder.size());
		Assertions.assertEquals(3, probe.maximum.get());
		Assertions.assertEquals(0, bulkhead.getInFlight());
		Assertions.assertEquals(0, bulkhead.getWaiting());
	}

	/**
	 * Tests that the limit is never exceeded.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("At most max-in-flight handlers hold a permit; everyone eventually gets one")
	public void testLimit() throws Exception {
		this.assertLimit(Thread.ofPlatform());
	}

	/**
	 * Tests the limit with virtual threads.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The limit holds with virtual threads")
	public void testLimitWithVirtualThreads() throws Exception {
		this.assertLimit(Thread.ofVirtual());
	}

	/**
	 * Tests that permits are granted in arrival order.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Waiters are granted in arrival order")
	public void testArrivalOrder() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("fifo", () -> 1, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final PermitHolder first = this.startGrantedHolder(bulkhead, "waiter-0", grantOrder, probe);
		final List<PermitHolder> queuedHolders = new ArrayList<>();
		for (int holderIndex = 1; holderIndex <= 5; holderIndex++) {
			queuedHolders.add(this.startQueuedHolder(bulkhead, "waiter-" + holderIndex, grantOrder, probe));
		}
		first.releaseAndWait();
		for (final PermitHolder holder : queuedHolders) {
			Assertions.assertTrue(holder.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
			holder.releaseAndWait();
		}
		Assertions.assertEquals(List.of("waiter-0", "waiter-1", "waiter-2", "waiter-3", "waiter-4", "waiter-5"), grantOrder);
		Assertions.assertEquals(1, probe.maximum.get());
	}

	/**
	 * Tests that a new arrival does not overtake queued waiters when the limit
	 * is raised.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A new arrival does not overtake queued waiters when the limit is raised")
	public void testNoOvertaking() throws Exception {
		final AtomicInteger limit = new AtomicInteger(1);
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("no-overtaking", limit::get, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final PermitHolder holder = this.startGrantedHolder(bulkhead, "holder", grantOrder, probe);
		final PermitHolder firstQueued = this.startQueuedHolder(bulkhead, "queued-1", grantOrder, probe);
		final PermitHolder secondQueued = this.startQueuedHolder(bulkhead, "queued-2", grantOrder, probe);
		limit.set(2);
		final PermitHolder arrival = this.startHolder(bulkhead, "arrival", grantOrder, probe);
		Assertions.assertTrue(firstQueued.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		JmsListenerBulkheadTest.waitUntil(() -> bulkhead.getWaiting() == 2, "second queued waiter and arrival waiting");
		holder.releaseAndWait();
		Assertions.assertTrue(secondQueued.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(1, arrival.granted.getCount());
		firstQueued.releaseAndWait();
		Assertions.assertTrue(arrival.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(List.of("holder", "queued-1", "queued-2", "arrival"), grantOrder);
		Assertions.assertEquals(2, probe.maximum.get());
	}

	/**
	 * Tests the wait timeout.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A wait timeout throws WAIT_TIMEOUT, leaves the queue and does not corrupt it")
	public void testWaitTimeout() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("timeout", () -> 1, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final PermitHolder holder = this.startGrantedHolder(bulkhead, "holder", grantOrder, probe);
		final long startNanos = System.nanoTime();
		final JmsListenerBulkheadException exception = Assertions.assertThrows(JmsListenerBulkheadException.class,
				() -> bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, Duration.ofMillis(200), () -> false));
		Assertions.assertTrue((System.nanoTime() - startNanos) >= TimeUnit.MILLISECONDS.toNanos(200));
		Assertions.assertEquals(JmsListenerBulkheadException.Reason.WAIT_TIMEOUT, exception.getReason());
		Assertions.assertEquals("timeout", exception.getGroup());
		Assertions.assertEquals(JmsListenerBulkheadTest.DESTINATION, exception.getDestination());
		Assertions.assertEquals(0, bulkhead.getWaiting());
		final PermitHolder next = this.startQueuedHolder(bulkhead, "next", grantOrder, probe);
		holder.releaseAndWait();
		Assertions.assertTrue(next.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		next.releaseAndWait();
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that a waiter leaves on container stop, keeping the other waiters'
	 * order.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A container stop ends the wait with CONTAINER_STOPPED; the other waiters keep their order")
	public void testContainerStopped() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("stop", () -> 1, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final AtomicBoolean stopped = new AtomicBoolean();
		final PermitHolder holder = this.startGrantedHolder(bulkhead, "holder", grantOrder, probe);
		final PermitHolder first = this.startQueuedHolder(bulkhead, "first", grantOrder, probe);
		final PermitHolder stopping = this.startHolder(Thread.ofPlatform(), bulkhead, "stopping", null, null, stopped::get, grantOrder, probe);
		JmsListenerBulkheadTest.waitUntil(() -> bulkhead.getWaiting() == 2, "stopping waiter queued");
		final PermitHolder last = this.startQueuedHolder(bulkhead, "last", grantOrder, probe);
		final long stopNanos = System.nanoTime();
		stopped.set(true);
		Assertions.assertTrue(stopping.finished.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertTrue((System.nanoTime() - stopNanos) < JmsListenerBulkhead.STATE_CHECK_INTERVAL.multipliedBy(8).toNanos());
		Assertions.assertEquals(JmsListenerBulkheadException.Reason.CONTAINER_STOPPED, stopping.failure.get().getReason());
		Assertions.assertEquals(2, bulkhead.getWaiting());
		holder.releaseAndWait();
		Assertions.assertTrue(first.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		first.releaseAndWait();
		Assertions.assertTrue(last.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		last.releaseAndWait();
		Assertions.assertEquals(List.of("holder", "first", "last"), grantOrder);
	}

	/**
	 * Tests that an interrupted waiter leaves with INTERRUPTED and without the
	 * interrupt flag.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An interrupted waiter ends with INTERRUPTED, the interrupt flag cleared")
	public void testInterrupted() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("interrupt", () -> 1, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final PermitHolder holder = this.startGrantedHolder(bulkhead, "holder", grantOrder, probe);
		final PermitHolder interrupted = this.startQueuedHolder(bulkhead, "interrupted", grantOrder, probe);
		interrupted.thread.interrupt();
		Assertions.assertTrue(interrupted.finished.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(JmsListenerBulkheadException.Reason.INTERRUPTED, interrupted.failure.get().getReason());
		Assertions.assertFalse(interrupted.interruptedAfterFailure.get());
		Assertions.assertEquals(0, bulkhead.getWaiting());
		holder.releaseAndWait();
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that an interruption racing a grant never leaks a permit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An interruption racing a grant never leaks a permit (500 rounds)")
	public void testAbortRacingGrant() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("race", () -> 1, Map.of());
		for (int round = 0; round < 500; round++) {
			final JmsListenerBulkheadPermit held = bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, null, () -> false);
			final AtomicReference<JmsListenerBulkheadPermit> waiterPermit = new AtomicReference<>();
			final AtomicReference<JmsListenerBulkheadException> waiterFailure = new AtomicReference<>();
			final Thread waiter = Thread.ofPlatform().name("bulkhead-test-race").start(() -> {
				try {
					waiterPermit.set(bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, null, () -> false));
				}
				catch (final JmsListenerBulkheadException exception) {
					waiterFailure.set(exception);
				}
			});
			JmsListenerBulkheadTest.waitUntil(() -> bulkhead.getWaiting() == 1, "race waiter queued");
			final CountDownLatch start = new CountDownLatch(1);
			final Thread releaser = Thread.ofPlatform().name("bulkhead-test-releaser").start(() -> {
				try {
					start.await();
					held.close();
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
			start.countDown();
			waiter.interrupt();
			releaser.join(JmsListenerBulkheadTest.WAIT.toMillis());
			waiter.join(JmsListenerBulkheadTest.WAIT.toMillis());
			Assertions.assertFalse(waiter.isAlive());
			Assertions.assertTrue((waiterPermit.get() == null) != (waiterFailure.get() == null), "either a permit or a failure");
			if (waiterPermit.get() != null) {
				waiterPermit.get().close();
			}
			Assertions.assertEquals(0, bulkhead.getInFlight());
			Assertions.assertEquals(0, bulkhead.getWaiting());
			try (JmsListenerBulkheadPermit fresh = bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, Duration.ofSeconds(1), () -> false)) {
				Assertions.assertFalse(fresh.hasWaited());
			}
		}
	}

	/**
	 * Tests reserved permits.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A reserved request is granted at once while its reserved permit is free; beyond it, it queues for shared permits in order")
	public void testReservedPermits() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("reserved", () -> 3, Map.of("critical", 1));
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final PermitHolder firstUnreserved = this.startGrantedHolder(bulkhead, "unreserved-1", grantOrder, probe);
		final PermitHolder secondUnreserved = this.startGrantedHolder(bulkhead, "unreserved-2", grantOrder, probe);
		final PermitHolder thirdUnreserved = this.startQueuedHolder(bulkhead, "unreserved-3", grantOrder, probe);
		final PermitHolder firstCritical = this.startReservedHolder(bulkhead, "critical-1", "critical", grantOrder, probe);
		Assertions.assertTrue(firstCritical.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(1, bulkhead.getInFlight("critical"));
		final PermitHolder secondCritical = this.startReservedHolder(bulkhead, "critical-2", "critical", grantOrder, probe);
		JmsListenerBulkheadTest.waitUntil(() -> bulkhead.getWaiting() == 2, "second critical request queued");
		Assertions.assertEquals(3, bulkhead.getInFlight());
		firstUnreserved.releaseAndWait();
		Assertions.assertTrue(thirdUnreserved.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(1, secondCritical.granted.getCount());
		secondUnreserved.releaseAndWait();
		Assertions.assertTrue(secondCritical.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(2, bulkhead.getInFlight("critical"));
		Assertions.assertEquals(List.of("unreserved-1", "unreserved-2", "critical-1", "unreserved-3", "critical-2"), grantOrder);
		Assertions.assertEquals(3, probe.maximum.get());
		firstCritical.releaseAndWait();
		secondCritical.releaseAndWait();
		thirdUnreserved.releaseAndWait();
		Assertions.assertEquals(0, bulkhead.getInFlight());
		Assertions.assertEquals(0, bulkhead.getInFlight("critical"));
	}

	/**
	 * Tests that idle reserved permits are not lent.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An idle reserved permit is not lent to unreserved listeners")
	public void testReservedPermitsAreStrict() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("strict", () -> 3, Map.of("critical", 1));
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		this.startGrantedHolder(bulkhead, "unreserved-1", grantOrder, probe);
		this.startGrantedHolder(bulkhead, "unreserved-2", grantOrder, probe);
		this.startQueuedHolder(bulkhead, "unreserved-3", grantOrder, probe);
		Assertions.assertEquals(2, bulkhead.getInFlight());
		Assertions.assertEquals(2, probe.maximum.get());
		Assertions.assertEquals(1, bulkhead.getReservedPermits("critical"));
	}

	/**
	 * Tests a limit changed at runtime.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A raised limit grants queued waiters; a lowered one grants nothing until in flight drops below it")
	public void testLiveLimit() throws Exception {
		final AtomicInteger limit = new AtomicInteger(1);
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("live", limit::get, Map.of());
		final InFlightProbe probe = new InFlightProbe();
		final List<String> grantOrder = new CopyOnWriteArrayList<>();
		final PermitHolder holder = this.startGrantedHolder(bulkhead, "holder", grantOrder, probe);
		final PermitHolder first = this.startQueuedHolder(bulkhead, "first", grantOrder, probe);
		final PermitHolder second = this.startQueuedHolder(bulkhead, "second", grantOrder, probe);
		final PermitHolder third = this.startQueuedHolder(bulkhead, "third", grantOrder, probe);
		limit.set(3);
		Assertions.assertTrue(first.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertTrue(second.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(3, bulkhead.getMaxInFlight());
		Assertions.assertEquals(3, bulkhead.getInFlight());
		Assertions.assertEquals(1, bulkhead.getWaiting());
		limit.set(1);
		holder.releaseAndWait();
		Assertions.assertEquals(1, bulkhead.getWaiting());
		first.releaseAndWait();
		Assertions.assertEquals(1, bulkhead.getWaiting());
		second.releaseAndWait();
		Assertions.assertTrue(third.granted.await(JmsListenerBulkheadTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		third.releaseAndWait();
		Assertions.assertEquals(List.of("holder", "first", "second", "third"), grantOrder);
		Assertions.assertEquals(1, new JmsListenerBulkhead("unclamped", () -> 1, Map.of()).getMaxInFlight());
		Assertions.assertEquals(3, new JmsListenerBulkhead("clamped", () -> 1, Map.of("critical", 2)).getMaxInFlight());
	}

	/**
	 * Tests that closing a permit twice releases it once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Closing a permit twice releases it once")
	public void testIdempotentClose() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("close", () -> 1, Map.of());
		final JmsListenerBulkheadPermit permit = bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, null, () -> false);
		permit.close();
		permit.close();
		Assertions.assertEquals(0, bulkhead.getInFlight());
		try (JmsListenerBulkheadPermit next = bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, null, () -> false)) {
			Assertions.assertEquals(JmsListenerBulkheadException.Reason.WAIT_TIMEOUT, Assertions.assertThrows(JmsListenerBulkheadException.class,
					() -> bulkhead.acquire(null, JmsListenerBulkheadTest.DESTINATION, Duration.ofMillis(100), () -> false)).getReason());
		}
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that a reservation needs at least one permit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A reservation without permits is rejected")
	public void testInvalidReservation() throws Exception {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new JmsListenerBulkhead("invalid", () -> 3, Map.of("critical", 0)));
	}

}
