# Service warm-up

A new JVM runs its code interpreted until the JIT compiles it. Taking traffic at once, it
answers slowly while the compiler catches up, and requests queue behind it. A service warm-up
makes real read-only calls before the instance takes traffic, so that the JIT compiles the
code they exercise first.

## Enabling

The warm-up is off by default. Until a service turns it on, the library registers none of its
beans, filters or guards, so a version bump changes nothing for services that do not use it.
To use it:

1. Set `org.coldis.library.service.warm-up.enabled=true` in the service's own properties (or
   environment).
2. Declare one or more `ServiceWarmUp` beans.

A service that declares warm-ups with the property off gets a warning at start, and its
warm-ups do not run. With the property on and no warm-up beans, nothing runs and the health
check is never held.

```java
@Component
public class OrderWarmUp implements ServiceWarmUp<Long> {

	@Override
	public List<Long> inputs() {
		return this.orderRepository.findRecentIds(PageRequest.of(0, 500));
	}

	@Override
	public void warmUp(final Long orderId) throws Exception {
		this.orderService.findById(orderId);
		this.orderService.findItems(orderId);
	}

}
```

- `inputs()` is read once. Each input is used at most once, so a cached method still runs its
  uncached path. Give heavy queries fewer inputs: each one is a real query on the database.
- `warmUp(input)` is one call, the equivalent of one request. It runs on several threads at once.
- Call the code behind the endpoints that get the most traffic, with the same kind of arguments.

## How it runs

- When the application is ready, the warm-ups run on their own threads (one per available
  processor by default), apart from the request threads, taking the warm-ups in turns.
- The health check answers 503 from the start until they stop, so that the orchestrator sends
  no traffic yet. The filter answers without reaching the repository.
- They stop when the JIT stays idle (under 100 ms of compilation per second) for 3 seconds
  after every running warm-up made 500 calls, or at the 60-second time limit, whichever comes
  first. A warm-up also stops alone when its inputs end, when they cannot be read, or after 20
  consecutive errors.
- It fails open: any error is logged and the health check opens, so the service takes traffic
  as it would without a warm-up. Keep the time limit well under the orchestrator's health
  check grace.
- Only web traffic is held, through the servlet health check. JMS listener containers start
  consuming when the context starts, before the warm-up, so queue work runs on code not yet
  compiled and shares the processors with the warm-up threads. A service without a servlet
  health check holds nothing.

## What a warm-up call cannot do

With the warm-up on, on warm-up threads only (other threads are not affected):

| Side effect | What happens |
|---|---|
| Transactions | Each call runs in a read-only transaction that is rolled back (on the single or primary transaction manager). The service's `@Transactional` methods run read-only, `REQUIRES_NEW` included: statements sent to the database at once (locks, native and bulk updates, inserts that need a generated key) are refused by it (PostgreSQL SQL state 25006) and fail the call, while changes to loaded entities, which would be written at commit, are discarded without an error and the call goes on. Any other new read-write transaction, from a repository, a `TransactionTemplate` or another transaction manager, is refused before it begins. |
| JMS | Connections and contexts from `MeteredJmsPoolConnectionFactory` (the pooled factories of `JmsConfigurationHelper.createPooledJmsConnectionFactory`) are refused. |
| HTTP | Requests through any `RestTemplate` bean are refused. |
| Caches | Writes, evictions and clears are dropped; reads work. |
| Statistics | Events and summary deltas are refused (they are written later on other threads). |

A refused side effect throws `ServiceWarmUpSideEffectException`, which fails the call and is
counted as an error. Code outside this library can skip its own side effects with
`ServiceWarmUpContext.isWarmingUp()`, for instance a log interceptor.

Warm-up calls must follow read paths only. A write path does not stop at its write: a discarded
entity change lets the call go on to whatever follows it, and the call counts as a success. A
warm-up that reports no failed calls on a path that writes is a sign that its writes are being
discarded.

Not covered, so warm-up calls must not use them:

- writes made outside any transaction (auto-commit), for instance a `JdbcTemplate` write in a
  `NOT_SUPPORTED` method;
- work handed to another thread (`@Async`, executors);
- HTTP clients other than `RestTemplate` beans (vendor client libraries, `WebClient`,
  `RestClient`, the JDK client);
- native JMS connection factories (`JmsConfigurationHelper.createNativeJmsConnectionFactory`).

A warm-up call that reaches one of these channels reaches real customers or paid vendors (a
bureau query, a text message, an e-mail), once per input on every instance start, and is
counted as a successful call. Before turning the warm-up on, check the warm-up's call paths for
outbound calls and notifications, for instance with a test that runs the warm-ups and asserts
that no outbound client was called. On the first deploy, compare the service's outbound query
and notification counts with an ordinary period of the same length.

A request-logging aspect on the warmed code also runs on warm-up calls. Unless it skips its
logging while `ServiceWarmUpContext.isWarmingUp()` is true, it either fails the calls (when its
transport is one of the refused side effects) or ships an entry per call from another thread as
if it were a real request. Make it skip before turning the warm-up on.

## Properties

All under `org.coldis.library.service.warm-up`, defaults in `service.properties`:

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Turns the warm-up on: its service, health check filter and guards. |
| `time-limit` | `60s` | Longest time before the health check opens. |
| `minimum-calls` | `500` | Calls each warm-up makes before an idle JIT can stop it. |
| `parallelism` | `0` | Threads; 0 or less means one per available processor. |
| `idle-compilation-millis-per-second` | `100` | JIT compilation rate under which the JIT counts as idle. |
| `idle-compilation-period` | `3s` | How long the JIT must stay idle. |
| `maximum-consecutive-errors` | `20` | Consecutive failed calls that stop a warm-up. |

## Observability

- At the end, an info log line for the run (duration, why it stopped, threads, JIT compilation
  time) and one per warm-up (calls, errors, why it stopped, median call duration in the first
  and in the last 100 calls). The first error of each warm-up is logged with its stack trace at
  warn, the others at debug.
- Metrics: timer `service.warm.up` (tag `outcome`) and counter `service.warm.up.calls` (tags
  `warm.up`, `outcome`).
