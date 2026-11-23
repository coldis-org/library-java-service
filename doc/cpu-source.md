# CPU source

The CPU count that limits expressed per CPU (threads or permits per CPU) multiply. It is
`org.coldis.library.thread.CpuLimits` in the core library (plain Java); this library only pins it from
its configuration.

## CPU count

First match:

1. `org.coldis.library.service.cpus` (a decimal): pins the count. Pinned once at startup by
   `CpuEnvironmentPostProcessor`, before any bean exists, so set it in Spring Boot config data
   (`application.properties`, profiles) or an environment variable, not in a `@PropertySource` file;
2. the cgroup v2 quota (`/sys/fs/cgroup/cpu.max`: quota / period);
3. the cgroup v1 quota (`cpu.cfs_quota_us / cpu.cfs_period_us`);
4. `Runtime.availableProcessors()`.

A quota is capped at `availableProcessors()`. The JVM rounds a fractional quota up (a quota of 2.08
CPUs reports 3 processors), which is why the quota itself is read. The count is read once and logged.

## Limits

`CpuLimits.getDefault().resolve(multiplier)` gives CPUs × multiplier, rounded up, at least 1.
`resolve(absolute, ratio, defaultRatio)` resolves, first match: the explicit absolute value, the
explicit ratio, the default ratio; a ratio of 0 means no limit.

Used by: the [listener bulkhead](jms-listener-bulkhead.md) (`max-in-flight-cpu-multiplier`) and the
Artemis remoting threads ([JMS threads](jms-threads.md)). Follow-up: the bounded listener pool, the
Artemis global executors (`DynamicThreadPoolFactory` multipliers) and the HTTP threads could use it;
they still multiply `availableProcessors()`.
