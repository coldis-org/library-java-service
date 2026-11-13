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
- They stop when the JIT stays idle (under 100 ms of compilation per second for 3 seconds)
  after each warm-up made 500 calls, or at the 60-second time limit, whichever comes first. A
  warm-up also stops alone when its inputs end, when they cannot be read, or after 20
  consecutive errors.
- It fails open: any error is logged and the health check opens, so the service takes traffic
  as it would without a warm-up. Keep the time limit well under the orchestrator's health
  check grace.

## What a warm-up call cannot do

With the warm-up on, on warm-up threads only (other threads are not affected):

| Side effect | What happens |
|---|---|
| Database writes | Each call runs in a read-only transaction that is rolled back, and every `@Transactional` method it reaches is read-only, `REQUIRES_NEW` included. The database refuses writes (PostgreSQL SQL state 25006). |
| JMS | Connections and contexts from `MeteredJmsPoolConnectionFactory` (the factories `JmsConfigurationHelper` creates) are refused. |
| HTTP | Requests through any `RestTemplate` bean are refused. |
| Caches | Writes, evictions and clears are dropped; reads work. |
| Statistics | Events and summary deltas are refused (they are written later on other threads). |

A refused side effect throws `ServiceWarmUpSideEffectException`, which fails the call and is
counted as an error. Code outside this library can skip its own side effects with
`ServiceWarmUpContext.isWarmingUp()`, for instance a log interceptor.

Not covered, so warm-up calls must not use them: work handed to another thread (`@Async`,
executors), HTTP clients other than `RestTemplate` beans, JMS connection factories built
outside `JmsConfigurationHelper`, and repositories of a second data source whose transaction
manager is not the primary one.

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
