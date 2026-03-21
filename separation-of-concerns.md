# Separation of Concerns: Service vs ServiceComponent

Refactoring to align with the architecture rule: **Service** = interface/entry point (HTTP/JMS), **ServiceComponent** = implementation (business logic).

## Changes

### 1. RepositoryHealthCheckService (done)
- Renamed `RepositoryHealthCheckService` → `RepositoryHealthCheckServiceComponent`
- Updated `HealthCheckService` to reference the new class name

### 2. KeyValueService (done — in coldis-library-java-persistence)
- Renamed `KeyValueService` → `KeyValueServiceComponent`
- Updated references in both persistence and service projects

### 2. BatchService (pending)
- Create `BatchService` interface with the business contract (constants + public methods)
- Rename current `BatchService` → `BatchServiceComponent` implementing the interface
  - Remove REST annotations, keep `@JmsListener`, `@Scheduled`, `@Transactional`
- Create thin `BatchController` delegating to `BatchService` for REST endpoints

## Not changed
- **TemplatingServiceComponent** — already a component, no API exposed
- **LocalizedMessageServiceComponent** — already a component, no API exposed
- **SecurityContextServiceComponent** — already a component, no API exposed
- **HealthCheckService** — already a thin controller delegating to component
- **PropertiesService** — infrastructure/admin tooling, minimal logic
