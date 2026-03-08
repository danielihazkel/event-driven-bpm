# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

This project is in planning phase. The codebase will be a **Java/Spring Boot** implementation of a Generic Event-Driven Orchestration Engine (EDOE) as defined in `prd.md`.

## Planned Tech Stack

- **Java** with **Spring Boot**
- **Spring Kafka** for messaging
- **Spring Data JPA** for persistence
- **PostgreSQL** as the state store
- **Apache Kafka (KRaft mode)** as the event bus
- **Docker Compose** for local infrastructure

## Common Commands (once project is initialized)

```bash
# Start infrastructure
docker compose up -d

# Build
./mvnw clean package

# Run
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test
./mvnw test -Dtest=ClassName#methodName
```

## Architecture

The system follows an event-driven choreography pattern with a central orchestrator acting as a state machine.

### Core Components

- **Orchestrator Service** — The "brain". Receives events, transitions state, publishes commands.
- **State Store (PostgreSQL)** — `process_instances` table tracks each running process. Key fields: `id` (UUID), `definition_name`, `current_step`, `context_data` (JSONB), `status` (`RUNNING`/`COMPLETED`/`FAILED`/`SUSPENDED`).
- **Kafka Topics** — `orchestrator-commands` (outbound to workers) and `worker-events` (inbound from workers).
- **Worker Services** — Generic task executors: consume a command by task type, do work, emit a `*_FINISHED` event back.

### Process Flow

```
Client → POST /start-flow → Orchestrator
  → DB: INSERT status=RUNNING, step=STEP_1
  → Kafka: publish {type: "STEP_1", pid, data}
     → Worker consumes, executes, publishes {type: "STEP_1_FINISHED", pid, output}
        → Orchestrator consumes, DB: UPDATE step=STEP_2, merge output into context_data
           → Repeat until final step
```

### Key Reliability Requirements

- **Persistence-before-publish**: DB state must be written before sending the next Kafka command (consider Transactional Outbox Pattern).
- **Idempotency**: Duplicate `*_FINISHED` events for the same step must be ignored.
- **Correlation**: Every Kafka message carries `processId` (in headers and body).
- **DLQ**: Dead Letter Queue for failed Kafka messages.
- **Timeouts**: Scheduled task to detect steps in `RUNNING` state beyond N minutes and mark them `STALLED`.

### Planned Key Classes

- `ProcessInstance` — JPA entity for `process_instances` table
- `TransitionService` — Takes `(processId, event)` → determines next command; holds step-transition `Map<String, String>` config
- Kafka `@KafkaListener` — Consumes `worker-events` topic
- Kafka producer — Publishes to `orchestrator-commands`
- `GET /status/{id}` — REST endpoint to query process state
