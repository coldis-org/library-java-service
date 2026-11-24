package org.coldis.library.test.service.jms;

import java.time.Duration;
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
import org.coldis.library.service.jms.JmsListenerBulkheadAdmission;
import org.coldis.library.service.jms.JmsListenerBulkheadException;
import org.coldis.library.service.jms.JmsListenerBulkheadPermit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate of {@link JmsListenerBulkhead} on its own: consumers wait before
 * they receive, in arrival order, with or without taking the permit at the
 * gate; consumers holding a message go first; reservations, stop, interruption
 * and live limits. Every wait is on a latch or on the bulkhead state, never a
 * fixed sleep.
 */
@DisplayName("JMS listener bulkhead — gate before receive")
public class JmsListenerBulkheadGateTest {

	/**
	 * Longest wait for a state the test expects.
	 */
	private static final Duration WAIT = Duration.ofSeconds(10);

	/**
	 * Destination label.
	 */
	private static final String DESTINATION = "bulkhead/gate";

	/**
	 * A consumer thread that passes the gate and keeps its admission until
	 * released.
	 */
	private static final class GateConsumer {

		/**
		 * Name (recorded in the admission order).
		 */
		private final String name;

		/**
		 * Counted down when the consumer passed the gate.
		 */
		private final CountDownLatch admitted = new CountDownLatch(1);

		/**
		 * Counted down by the test to close the admission.
		 */
		private final CountDownLatch release = new CountDownLatch(1);

		/**
		 * Counted down once the admission is closed or the wait failed.
		 */
		private final CountDownLatch finished = new CountDownLatch(1);

		/**
		 * Admission, once passed.
		 */
		private final AtomicReference<JmsListenerBulkheadAdmission> admission = new AtomicReference<>();

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
		private GateConsumer(final String name) {
			this.name = name;
		}

		/**
		 * Waits until the consumer passed the gate.
		 *
		 * @throws InterruptedException If interrupted.
		 */
		private void awaitAdmitted() throws InterruptedException {
			Assertions.assertTrue(this.admitted.await(JmsListenerBulkheadGateTest.WAIT.toMillis(), TimeUnit.MILLISECONDS), this.name + " was not admitted");
		}

		/**
		 * Closes the admission and waits until it is closed.
		 *
		 * @throws InterruptedException If interrupted.
		 */
		private void releaseAndWait() throws InterruptedException {
			this.release.countDown();
			Assertions.assertTrue(this.finished.await(JmsListenerBulkheadGateTest.WAIT.toMillis(), TimeUnit.MILLISECONDS), this.name + " did not finish");
		}

	}

	/**
	 * Consumers started by the current test (released after it).
	 */
	private final List<GateConsumer> consumers = new CopyOnWriteArrayList<>();

	/**
	 * Permits held by the current test (released after it).
	 */
	private final List<JmsListenerBulkheadPermit> permits = new CopyOnWriteArrayList<>();

	/**
	 * Releases every consumer and permit left by a test.
	 *
	 * @throws InterruptedException If interrupted.
	 */
	@AfterEach
	public void releaseAll() throws InterruptedException {
		for (final GateConsumer consumer : this.consumers) {
			consumer.release.countDown();
			consumer.thread.interrupt();
			consumer.thread.join(JmsListenerBulkheadGateTest.WAIT.toMillis());
		}
		this.consumers.clear();
		this.permits.forEach(JmsListenerBulkheadPermit::close);
		this.permits.clear();
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
		final long deadline = System.nanoTime() + JmsListenerBulkheadGateTest.WAIT.toNanos();
		while (!condition.getAsBoolean() && (System.nanoTime() < deadline)) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		}
		Assertions.assertTrue(condition.getAsBoolean(), description);
	}

	/**
	 * Takes a permit for a received message at once (the test asserts it was
	 * immediate) and keeps it until the end of the test.
	 *
	 * @param  bulkhead                     Bulkhead.
	 * @param  reservation                  Reservation.
	 * @return                              The permit.
	 * @throws JmsListenerBulkheadException If the wait ended without a permit.
	 */
	private JmsListenerBulkheadPermit holdPermit(
			final JmsListenerBulkhead bulkhead,
			final String reservation) throws JmsListenerBulkheadException {
		final JmsListenerBulkheadPermit permit = bulkhead.acquire(reservation, JmsListenerBulkheadGateTest.DESTINATION, null, () -> false);
		Assertions.assertFalse(permit.hasWaited());
		this.permits.add(permit);
		return permit;
	}

	/**
	 * Starts a consumer that waits at the gate.
	 *
	 * @param  bulkhead      Bulkhead.
	 * @param  name          Name.
	 * @param  reservation   Reservation.
	 * @param  takesPermit   If the permit is taken at the gate.
	 * @param  stopped       If the container stopped.
	 * @param  admittedOrder Admission order (names).
	 * @return               The consumer.
	 */
	private GateConsumer startConsumer(
			final JmsListenerBulkhead bulkhead,
			final String name,
			final String reservation,
			final boolean takesPermit,
			final BooleanSupplier stopped,
			final List<String> admittedOrder) {
		final GateConsumer consumer = new GateConsumer(name);
		consumer.thread = Thread.ofPlatform().name("bulkhead-gate-test-" + name).unstarted(() -> {
			try (JmsListenerBulkheadAdmission admission = bulkhead.admit(reservation, takesPermit, JmsListenerBulkheadGateTest.DESTINATION, stopped)) {
				admittedOrder.add(name);
				consumer.admission.set(admission);
				consumer.admitted.countDown();
				consumer.release.await();
			}
			catch (final JmsListenerBulkheadException exception) {
				consumer.interruptedAfterFailure.set(Thread.currentThread().isInterrupted());
				consumer.failure.set(exception);
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			finally {
				consumer.finished.countDown();
			}
		});
		this.consumers.add(consumer);
		consumer.thread.start();
		return consumer;
	}

	/**
	 * Starts a consumer and waits until it is queued at the gate.
	 *
	 * @param  bulkhead      Bulkhead.
	 * @param  name          Name.
	 * @param  reservation   Reservation.
	 * @param  takesPermit   If the permit is taken at the gate.
	 * @param  admittedOrder Admission order (names).
	 * @return               The consumer.
	 */
	private GateConsumer startQueuedConsumer(
			final JmsListenerBulkhead bulkhead,
			final String name,
			final String reservation,
			final boolean takesPermit,
			final List<String> admittedOrder) {
		final int waitingBefore = bulkhead.getWaiting();
		final GateConsumer consumer = this.startConsumer(bulkhead, name, reservation, takesPermit, () -> false, admittedOrder);
		JmsListenerBulkheadGateTest.waitUntil(() -> bulkhead.getWaiting() == (waitingBefore + 1), name + " was not queued");
		return consumer;
	}

	/**
	 * Tests the permit taken at the gate: counted at once, handed to the
	 * handler once, released by the admission when no handler took it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A permit taken at the gate is counted, handed to the handler once, and released by the admission when unused")
	public void testPermitTakenAtGate() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-permit", () -> 2, Map.of());

		// No message received: closing the admission gives the permit back.
		try (JmsListenerBulkheadAdmission admission = bulkhead.admit(null, true, JmsListenerBulkheadGateTest.DESTINATION, () -> false)) {
			Assertions.assertTrue(admission.hasPermit());
			Assertions.assertEquals(0L, admission.getWaitNanos());
			Assertions.assertEquals(1, bulkhead.getInFlight());
		}
		Assertions.assertEquals(0, bulkhead.getInFlight());

		// A message received: the handler runs on the permit taken at the gate.
		try (JmsListenerBulkheadAdmission admission = bulkhead.admit(null, true, JmsListenerBulkheadGateTest.DESTINATION, () -> false)) {
			try (JmsListenerBulkheadPermit permit = admission.takePermit()) {
				Assertions.assertNotNull(permit);
				Assertions.assertFalse(permit.hasWaited());
				Assertions.assertNull(admission.takePermit());
				Assertions.assertEquals(1, bulkhead.getInFlight());
			}
			Assertions.assertEquals(0, bulkhead.getInFlight());
		}
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that a consumer of an idle queue takes nothing at the gate and does
	 * not keep a busy listener out.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A consumer without permit at the gate takes nothing: idle pollers never keep a busy listener out")
	public void testGateWithoutPermitTakesNothing() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-idle", () -> 1, Map.of());
		final List<JmsListenerBulkheadAdmission> idleAdmissions = new CopyOnWriteArrayList<>();
		for (int consumerIndex = 0; consumerIndex < 10; consumerIndex++) {
			idleAdmissions.add(bulkhead.admit(null, false, JmsListenerBulkheadGateTest.DESTINATION, () -> false));
		}
		Assertions.assertEquals(0, bulkhead.getInFlight());
		Assertions.assertTrue(bulkhead.isGateOpen(null));

		// A busy listener still passes at once and takes the only permit.
		try (JmsListenerBulkheadAdmission busyAdmission = bulkhead.admit(null, true, JmsListenerBulkheadGateTest.DESTINATION, () -> false)) {
			Assertions.assertEquals(0L, busyAdmission.getWaitNanos());
			Assertions.assertEquals(1, bulkhead.getInFlight());
			Assertions.assertFalse(bulkhead.isGateOpen(null));

			// With the permit taken, an idle poller now waits at the gate too.
			final GateConsumer idleConsumer = this.startQueuedConsumer(bulkhead, "idle", null, false, new CopyOnWriteArrayList<>());
			Assertions.assertEquals(0, bulkhead.getWaitingWithMessage());
			Assertions.assertEquals(1, idleConsumer.admitted.getCount());
		}
		idleAdmissions.forEach(JmsListenerBulkheadAdmission::close);
		JmsListenerBulkheadGateTest.waitUntil(() -> bulkhead.getWaiting() == 0, "idle poller passed once the permit was free");
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that gated consumers pass in arrival order.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Gated consumers pass in arrival order, and a new arrival does not overtake them")
	public void testGateArrivalOrder() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-order", () -> 1, Map.of());
		final JmsListenerBulkheadPermit running = this.holdPermit(bulkhead, null);
		final List<String> admittedOrder = new CopyOnWriteArrayList<>();
		final List<GateConsumer> queued = new CopyOnWriteArrayList<>();
		for (int consumerIndex = 0; consumerIndex < 5; consumerIndex++) {
			queued.add(this.startQueuedConsumer(bulkhead, "consumer-" + consumerIndex, null, true, admittedOrder));
		}
		Assertions.assertFalse(bulkhead.isGateOpen(null));
		running.close();
		for (final GateConsumer consumer : queued) {
			consumer.awaitAdmitted();
			Assertions.assertEquals(1, bulkhead.getInFlight());
			// A new arrival queues behind the remaining ones.
			Assertions.assertFalse(bulkhead.isGateOpen(null));
			consumer.releaseAndWait();
		}
		Assertions.assertEquals(List.of("consumer-0", "consumer-1", "consumer-2", "consumer-3", "consumer-4"), admittedOrder);
		Assertions.assertEquals(0, bulkhead.getInFlight());
		Assertions.assertEquals(0, bulkhead.getWaiting());
	}

	/**
	 * Tests that consumers holding a message are granted before gated ones.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A consumer holding a received message is granted before consumers waiting at the gate")
	public void testMessageWaitersFirst() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-message-first", () -> 1, Map.of());
		final JmsListenerBulkheadPermit running = this.holdPermit(bulkhead, null);
		final List<String> admittedOrder = new CopyOnWriteArrayList<>();
		final GateConsumer gated = this.startQueuedConsumer(bulkhead, "gated", null, true, admittedOrder);
		final AtomicReference<JmsListenerBulkheadPermit> heldPermit = new AtomicReference<>();
		final Thread holdingThread = Thread.ofPlatform().name("bulkhead-gate-test-holding").start(() -> {
			try {
				heldPermit.set(bulkhead.acquire(null, JmsListenerBulkheadGateTest.DESTINATION, null, () -> false));
				admittedOrder.add("holding");
			}
			catch (final JmsListenerBulkheadException exception) {
				throw new IllegalStateException(exception);
			}
		});
		try {
			JmsListenerBulkheadGateTest.waitUntil(() -> bulkhead.getWaitingWithMessage() == 1, "holding consumer queued");
			Assertions.assertEquals(2, bulkhead.getWaiting());
			running.close();
			holdingThread.join(JmsListenerBulkheadGateTest.WAIT.toMillis());
		}
		finally {
			holdingThread.interrupt();
		}
		Assertions.assertEquals(List.of("holding"), admittedOrder);
		Assertions.assertTrue(heldPermit.get().hasWaited());
		Assertions.assertEquals(1, gated.admitted.getCount());
		heldPermit.get().close();
		gated.awaitAdmitted();
		Assertions.assertEquals(List.of("holding", "gated"), admittedOrder);
		gated.releaseAndWait();
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that a consumer without permit is woken in its turn without taking
	 * capacity, and does not pass before the waiters ahead of it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A gated consumer without permit passes in its turn and takes nothing; the next one takes the free permit")
	public void testGateWithoutPermitInOrder() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-mixed", () -> 1, Map.of());
		final JmsListenerBulkheadPermit running = this.holdPermit(bulkhead, null);
		final List<String> admittedOrder = new CopyOnWriteArrayList<>();
		final GateConsumer first = this.startQueuedConsumer(bulkhead, "first-with-permit", null, true, admittedOrder);
		final GateConsumer idle = this.startQueuedConsumer(bulkhead, "idle-without-permit", null, false, admittedOrder);
		final GateConsumer last = this.startQueuedConsumer(bulkhead, "last-with-permit", null, true, admittedOrder);

		// The release admits the first one only: it takes the permit.
		running.close();
		first.awaitAdmitted();
		Assertions.assertEquals(1, bulkhead.getInFlight());
		Assertions.assertEquals(2, bulkhead.getWaiting());

		// Its release admits the idle one (taking nothing) and the last one.
		first.releaseAndWait();
		idle.awaitAdmitted();
		last.awaitAdmitted();
		Assertions.assertFalse(idle.admission.get().hasPermit());
		Assertions.assertTrue(last.admission.get().hasPermit());
		Assertions.assertEquals(1, bulkhead.getInFlight());
		Assertions.assertEquals(List.of("first-with-permit", "idle-without-permit", "last-with-permit"), admittedOrder);
		idle.releaseAndWait();
		Assertions.assertEquals(1, bulkhead.getInFlight());
		last.releaseAndWait();
		Assertions.assertEquals(0, bulkhead.getInFlight());
	}

	/**
	 * Tests that a reserved listener passes the gate while its reserved permit
	 * is free, whatever the queue holds.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A reserved listener passes the gate at once while its reserved permit is free; unreserved ones wait for shared permits")
	public void testGateReservation() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-reserved", () -> 3, Map.of("critical", 1));
		this.holdPermit(bulkhead, null);
		this.holdPermit(bulkhead, null);
		final List<String> admittedOrder = new CopyOnWriteArrayList<>();
		final GateConsumer unreserved = this.startQueuedConsumer(bulkhead, "unreserved", null, true, admittedOrder);
		Assertions.assertFalse(bulkhead.isGateOpen(null));
		Assertions.assertTrue(bulkhead.isGateOpen("critical"));

		try (JmsListenerBulkheadAdmission critical = bulkhead.admit("critical", true, JmsListenerBulkheadGateTest.DESTINATION, () -> false)) {
			Assertions.assertEquals(0L, critical.getWaitNanos());
			Assertions.assertEquals(3, bulkhead.getInFlight());
			Assertions.assertEquals(1, bulkhead.getInFlight("critical"));
			// The reserved permit is taken: the next critical consumer queues for shared permits.
			Assertions.assertFalse(bulkhead.isGateOpen("critical"));
		}
		// The free reserved permit is not lent to the unreserved consumer.
		Assertions.assertEquals(1, unreserved.admitted.getCount());
		Assertions.assertEquals(2, bulkhead.getInFlight());
	}

	/**
	 * Tests that a container stop and an interruption end the gate wait without
	 * leaking capacity, and that the other waiters keep their order.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A container stop or an interruption ends the gate wait (CONTAINER_STOPPED, INTERRUPTED with the flag cleared); nothing leaks")
	public void testGateStopAndInterrupt() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-stop", () -> 1, Map.of());
		final JmsListenerBulkheadPermit running = this.holdPermit(bulkhead, null);
		final List<String> admittedOrder = new CopyOnWriteArrayList<>();
		final AtomicBoolean stopped = new AtomicBoolean();
		final GateConsumer first = this.startQueuedConsumer(bulkhead, "first", null, true, admittedOrder);
		final int waitingBefore = bulkhead.getWaiting();
		final GateConsumer stopping = this.startConsumer(bulkhead, "stopping", null, true, stopped::get, admittedOrder);
		JmsListenerBulkheadGateTest.waitUntil(() -> bulkhead.getWaiting() == (waitingBefore + 1), "stopping consumer queued");
		final GateConsumer interrupted = this.startQueuedConsumer(bulkhead, "interrupted", null, false, admittedOrder);
		final GateConsumer last = this.startQueuedConsumer(bulkhead, "last", null, true, admittedOrder);

		stopped.set(true);
		Assertions.assertTrue(stopping.finished.await(JmsListenerBulkheadGateTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(JmsListenerBulkheadException.Reason.CONTAINER_STOPPED, stopping.failure.get().getReason());
		interrupted.thread.interrupt();
		Assertions.assertTrue(interrupted.finished.await(JmsListenerBulkheadGateTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
		Assertions.assertEquals(JmsListenerBulkheadException.Reason.INTERRUPTED, interrupted.failure.get().getReason());
		Assertions.assertFalse(interrupted.interruptedAfterFailure.get());
		Assertions.assertEquals(2, bulkhead.getWaiting());

		running.close();
		first.awaitAdmitted();
		first.releaseAndWait();
		last.awaitAdmitted();
		last.releaseAndWait();
		Assertions.assertEquals(List.of("first", "last"), admittedOrder);
		Assertions.assertEquals(0, bulkhead.getInFlight());
		Assertions.assertEquals(0, bulkhead.getWaiting());
	}

	/**
	 * Tests that an interruption racing a gate grant never leaks a permit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An interruption racing a gate grant never leaks a permit (300 rounds)")
	public void testGateAbortRacingGrant() throws Exception {
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-race", () -> 1, Map.of());
		for (int round = 0; round < 300; round++) {
			final JmsListenerBulkheadPermit running = bulkhead.acquire(null, JmsListenerBulkheadGateTest.DESTINATION, null, () -> false);
			final GateConsumer consumer = this.startQueuedConsumer(bulkhead, "race-" + round, null, (round % 2) == 0, new CopyOnWriteArrayList<>());
			consumer.release.countDown();
			consumer.thread.interrupt();
			running.close();
			// Whether the grant or the interruption won, nothing stays counted.
			Assertions.assertTrue(consumer.finished.await(JmsListenerBulkheadGateTest.WAIT.toMillis(), TimeUnit.MILLISECONDS));
			JmsListenerBulkheadGateTest.waitUntil(() -> bulkhead.getInFlight() == 0, "no permit leaked in round");
			Assertions.assertEquals(0, bulkhead.getWaiting());
			this.consumers.remove(consumer);
		}
		try (JmsListenerBulkheadAdmission admission = bulkhead.admit(null, true, JmsListenerBulkheadGateTest.DESTINATION, () -> false)) {
			Assertions.assertEquals(0L, admission.getWaitNanos());
		}
	}

	/**
	 * Tests that a raised limit opens the gate for the queued consumers.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A raised limit admits queued gate waiters without a release")
	public void testGateLiveLimit() throws Exception {
		final AtomicInteger limit = new AtomicInteger(1);
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead("gate-live", limit::get, Map.of());
		this.holdPermit(bulkhead, null);
		final List<String> admittedOrder = new CopyOnWriteArrayList<>();
		final GateConsumer first = this.startQueuedConsumer(bulkhead, "first", null, true, admittedOrder);
		final GateConsumer second = this.startQueuedConsumer(bulkhead, "second", null, true, admittedOrder);
		limit.set(2);
		first.awaitAdmitted();
		Assertions.assertEquals(1, second.admitted.getCount());
		Assertions.assertEquals(2, bulkhead.getInFlight());
		limit.set(3);
		second.awaitAdmitted();
		Assertions.assertEquals(List.of("first", "second"), admittedOrder);
		Assertions.assertEquals(3, bulkhead.getInFlight());
	}

}
