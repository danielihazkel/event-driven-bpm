# Event-Driven Orchestration Engine (EDOE)

A generic event-driven process orchestrator built with Spring Boot, Apache Kafka, and PostgreSQL. It runs a configurable step-based workflow: each step dispatches a command to a worker via Kafka, waits for a `*_FINISHED` event, then transitions to the next step.

## Prerequisites

- Java 21+
- Maven (or use the included `mvnw` wrapper)
- Docker and Docker Compose

## Quick Start

### 1. Start infrastructure

```bash
docker compose up -d
```

This starts:
- **Kafka** (KRaft mode) on `localhost:9092`
- **PostgreSQL** on `localhost:5432`, database `edoe`, user `edoe`, password `edoe`

### 2. Build

```bash
./mvnw clean package -DskipTests
```

### 3. Run

```bash
./mvnw spring-boot:run
```

The application starts on **http://localhost:8080**.

### 4. Run tests

```bash
./mvnw test
```

Tests use an in-memory H2 database and mock Kafka — no Docker required.

---

## API

### Start a flow

```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "myFlow", "initialData": {"userId": 42}}'
```

Response:

```json
{"processId": "550e8400-e29b-41d4-a716-446655440000"}
```

### Check status

```bash
curl -s http://localhost:8080/status/550e8400-e29b-41d4-a716-446655440000
```

Response:

```json
{
  "processId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "step": "COMPLETED",
  "context": "{\"userId\":42,\"step1Result\":\"done\",\"step2Result\":\"done\"}"
}
```

Possible `status` values: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`.

---

## How It Works

```
POST /start-flow
  → DB: insert process (status=RUNNING, step=STEP_1)
  → Outbox: queue STEP_1 command
  → Outbox poller: publish command to Kafka topic "orchestrator-commands"
     → Worker: consume command, execute task, publish STEP_1_FINISHED to "worker-events"
        → Orchestrator: consume event, DB: update step=STEP_2, merge output into context
           → Outbox: queue STEP_2 command
              → ... repeat until final step → status=COMPLETED
```

**Reliability features:**
- **Transactional Outbox** — DB write and outbox entry happen in the same transaction; a scheduler polls and publishes, preventing lost messages on crash.
- **Idempotency** — duplicate `*_FINISHED` events for an already-advanced step are silently ignored.
- **Dead Letter Queue (DLQ)** — failed Kafka messages are retried 3 times (1 s apart) then routed to `worker-events.DLT` / `orchestrator-commands.DLT`.
- **Step-Level Timeouts** — a scheduler scans for steps stuck in `RUNNING` for more than 30 minutes and marks them `STALLED`.

---

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|---|---|---|
| `edoe.orchestrator.step-timeout-minutes` | `30` | Minutes before a running step is marked STALLED |
| `edoe.orchestrator.stalled-check-interval-ms` | `60000` | How often the timeout scanner runs |
| `edoe.orchestrator.outbox-poll-interval-ms` | `1000` | How often the outbox poller runs |

---

## Project Structure

```
src/main/java/com/edoe/orchestrator/
├── config/
│   ├── KafkaConsumerConfig.java   # DLQ error handler
│   └── KafkaTopicConfig.java      # Topic declarations
├── controller/
│   └── ProcessController.java     # REST endpoints
├── dto/
│   ├── OrchestratorMessage.java   # Kafka message shape
│   └── StartFlowRequest.java      # POST /start-flow body
├── entity/
│   ├── OutboxEvent.java           # Outbox table entity
│   ├── ProcessInstance.java       # process_instances table entity
│   └── ProcessStatus.java         # RUNNING/COMPLETED/FAILED/SUSPENDED/STALLED
├── listener/
│   └── WorkerEventListener.java   # Consumes "worker-events" topic
├── repository/
│   ├── OutboxEventRepository.java
│   └── ProcessInstanceRepository.java
├── service/
│   ├── CommandPublisherService.java   # Kafka producer
│   ├── OutboxPublisherService.java    # Scheduled outbox poller
│   ├── StepTimeoutService.java        # Scheduled stall detector
│   └── TransitionService.java         # State machine logic
└── worker/
    ├── WorkerCommandListener.java     # Consumes "orchestrator-commands" topic
    ├── WorkerEventPublisherService.java
    ├── WorkerTask.java                # Interface for task implementations
    ├── StepOneWorkerTask.java
    └── StepTwoWorkerTask.java
```

## Kafka Topics

| Topic | Direction | Description |
|---|---|---|
| `orchestrator-commands` | Orchestrator → Workers | Commands dispatched per step |
| `worker-events` | Workers → Orchestrator | Completion events (`*_FINISHED`) |
| `orchestrator-commands.DLT` | — | Dead letters from command processing |
| `worker-events.DLT` | — | Dead letters from event processing |
