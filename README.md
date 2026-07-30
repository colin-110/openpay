# OpenPay

OpenPay is a production-grade payment gateway portfolio project built to demonstrate realistic backend architecture, distributed systems patterns, and production engineering practices.

## Phase 1 Scope

Phase 1 establishes the platform foundation:

- multi-module Maven monorepo
- Spring Boot service skeletons
- PostgreSQL, Redis, and Kafka local infrastructure
- Flyway baseline wiring
- Actuator and Prometheus metrics
- correlation ID propagation
- architecture and roadmap documentation

Business payment workflows come in later phases.

## Phase 2 Progress

The first slice of Phase 2 is now implemented:

- merchant onboarding in `merchant-service`
- API key issuance and validation in `auth-service`

Current endpoints:

- `POST /api/v1/merchants`
- `GET /api/v1/merchants/{merchantId}`
- `GET /api/v1/merchants?page=0&size=20`
- `POST /api/v1/api-keys`
- `POST /api/v1/auth/validate-key`

## Repository Layout

```text
docs/
libs/
  common-observability/
platform/
  docker/
  observability/
services/
  auth-service/
  gateway-service/
  merchant-service/
  payment-service/
```

## Why This Structure

- `services/` keeps deployable applications isolated.
- `libs/` holds cross-cutting code that multiple services can share.
- `platform/` stores deployment and infrastructure assets instead of mixing them with app code.
- `docs/` captures architecture decisions before feature implementation.

If you're new to Spring Boot, think of each service as a separate backend app with its own dependencies, config, and database ownership.

## Getting Started

### 1. Start infrastructure

```powershell
docker compose -f platform/docker/docker-compose.yml up -d
```

### 2. Build the project

On Windows:

```powershell
.\mvnw.cmd clean test
```

On macOS/Linux:

```bash
./mvnw clean test
```

### 3. Run a service

Example:

```powershell
.\mvnw.cmd -pl services/gateway-service -am spring-boot:run
```

### 4. Verify

- Gateway ping: `http://localhost:8080/api/v1/ping`
- Gateway health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

## Spring Boot Basics

### What `@SpringBootApplication` does

It is the main annotation that tells Spring Boot:

- start the application
- scan this package and subpackages for components
- auto-configure common framework behavior

### What `application.yml` is

This is each service's runtime configuration file. It defines:

- port numbers
- database connection settings
- Kafka and Redis addresses
- actuator settings

### What Actuator is

Spring Boot Actuator exposes operational endpoints like:

- `/actuator/health`
- `/actuator/info`
- `/actuator/prometheus`

These are standard in production systems because monitoring and orchestration depend on them.

### What Flyway is

Flyway manages database schema changes through versioned SQL files. Instead of manually editing databases, you commit migrations and let services apply them safely on startup.

## How To Study Phase 2

For `merchant-service`, read files in this order:

1. `MerchantController`
2. `MerchantService`
3. `MerchantRepository`
4. `Merchant`
5. `V2__create_merchants.sql`

For `auth-service`, read files in this order:

1. `AuthController`
2. `ApiKeyService`
3. `ApiKeyRepository`
4. `ApiKey`
5. `V2__create_api_keys.sql`

That order matches the request flow:

- HTTP hits the controller
- controller calls the service
- service uses the repository
- repository persists the entity
- Flyway migration defines the table

## Phase 1 Deliverables Included

- software architecture document
- roadmap
- Maven parent project
- baseline service skeletons
- common correlation ID filter
- local infrastructure compose file

## Next Phase

Phase 2 will introduce merchant identity, API key management, and authentication flows.
