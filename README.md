# Event-Driven Orchestration Engine (EDOE)

A generic, event-driven process orchestrator built with Spring Boot, Apache Kafka, and PostgreSQL. Processes are defined as graphs of steps; each step dispatches a command to a worker via Kafka, waits for a `*_FINISHED` event, evaluates conditional transitions, and moves to the next step.

## Prerequisites

- Java 21+
- Maven (or use the included `mvnw` wrapper)
- Docker and Docker Compose

---

## Quick Start

### 1. Start infrastructure

```bash
docker compose up -d
```

Starts:
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
Swagger UI is available at **http://localhost:8080/swagger-ui.html**.

### 4. Run tests

```bash
./mvnw test
```

Tests use an in-memory H2 database and mock Kafka — no Docker required.

---

## How It Works

```
POST /start-flow
  → DB: insert process (status=RUNNING, step=<initialStep>)
  → Outbox: queue first command
  → Outbox poller: publish command to Kafka "orchestrator-commands"
     → Worker: consume, execute, publish <STEP>_FINISHED to "worker-events"
        → Orchestrator: consume event, evaluate conditional transitions
           → DB: update step to next, merge output into context_data
              → Outbox: queue next command … repeat until COMPLETED
```

**Reliability guarantees:**

| Feature | Detail |
|---|---|
| Transactional Outbox | DB write and outbox entry are in one transaction; a scheduler publishes the outbox, preventing lost messages on crash |
| Idempotency | Duplicate `*_FINISHED` events for an already-advanced step are silently ignored |
| Dead Letter Queue | Failed Kafka messages are retried 3 times (1 s apart) then routed to `*.DLT` topics |
| Step Timeouts | A scheduler marks steps `STALLED` if they stay `RUNNING` beyond the configured limit |
| Conditional Transitions | Each transition can carry a SpEL condition; branches are evaluated top-to-bottom, first match wins |
| Parallel Fork / Join | A single transition rule can fan out to N parallel steps; the engine waits for all to complete before advancing to the join step |
| Human-in-the-loop (Signals) | Processes can be `SUSPENDED` to wait for external named signals via API, which evaluate transitions normally |
| Saga Rollback / Compensation | Steps can map to compensation steps; on `<step>_FAILED` event, the engine will natively walk backwards and dispatch compensation commands |

---

## Process Definitions

A process definition declares the workflow graph. Each event type maps to an ordered list of **transition rules**. There are two rule types:

### Single-next rule (conditional or unconditional)

Branches are evaluated top-to-bottom; the first whose condition matches is taken. A missing or null `condition` is an unconditional default (always matches).

```json
{
  "EVENT_TYPE_FINISHED": [
    { "condition": "<SpEL expression>", "next": "STEP_IF_TRUE" },
    { "next": "DEFAULT_STEP" }
  ]
}
```

Conditions use [Spring Expression Language (SpEL)](https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#expressions) evaluated against the **merged process context**. Each context key is available as `#keyName`.

### Fork rule (parallel fan-out)

When a rule carries `parallel` instead of `next`, the engine dispatches all listed steps simultaneously, waits for every `*_FINISHED` event, then advances to `joinStep`.

```json
{
  "PREPARE_FINISHED": [
    { "parallel": ["VALIDATE_CREDIT", "VERIFY_IDENTITY"], "joinStep": "APPROVE_LOAN" }
  ]
}
```

Duplicate `*_FINISHED` events for an already-completed branch are ignored (idempotency is preserved per-branch).

### Suspend rule (Human-in-the-loop)

When a rule carries `suspend: true`, the process stops at the next step and moves to `SUSPENDED` status. It waits for an external signal API call to resume.

```json
{
  "VALIDATE_CREDIT_FINISHED": [
    { "condition": "#creditScore > 700", "next": "AUTO_APPROVE" },
    { "next": "MANUAL_REVIEW", "suspend": true }
  ]
}
```

### Compensations (Saga Rollback)

Process Definitions can accept a `compensations` map. If a step fails with a `_FAILED` event (e.g., `CHARGE_PAYMENT_FAILED`), the engine automatically enters a compensating state and works backwards through the successfully completed steps, dispatching their corresponding mapped compensation tasks.

```json
{
  "name": "PAYMENT_SAGA",
  "initialStep": "RESERVE_INVENTORY",
  "transitions": { ... },
  "compensations": {
    "RESERVE_INVENTORY": "UNDO_RESERVE_INVENTORY",
    "CHARGE_PAYMENT": "REFUND_PAYMENT"
  }
}
```

### Linear example

```json
{
  "name": "MY_FLOW",
  "initialStep": "STEP_1",
  "transitions": {
    "STEP_1_FINISHED": [{ "next": "STEP_2" }],
    "STEP_2_FINISHED": [{ "next": "COMPLETED" }]
  }
}
```

### Conditional example

```json
{
  "name": "LOAN_APPROVAL",
  "initialStep": "VALIDATE_CREDIT",
  "transitions": {
    "VALIDATE_CREDIT_FINISHED": [
      { "condition": "#creditScore > 700", "next": "AUTO_APPROVE" },
      { "next": "MANUAL_REVIEW" }
    ],
    "MANUAL_REVIEW_FINISHED": [
      { "condition": "#approved == true", "next": "DISBURSE_FUNDS" },
      { "next": "SEND_REJECTION" }
    ],
    "AUTO_APPROVE_FINISHED":    [{ "next": "DISBURSE_FUNDS" }],
    "DISBURSE_FUNDS_FINISHED":  [{ "next": "COMPLETED" }],
    "SEND_REJECTION_FINISHED":  [{ "next": "COMPLETED" }]
  }
}
```

### Parallel fork example

```json
{
  "name": "PARALLEL_FLOW",
  "initialStep": "PREPARE_APPLICATION",
  "transitions": {
    "PREPARE_APPLICATION_FINISHED": [
      { "parallel": ["VALIDATE_CREDIT", "VERIFY_IDENTITY"], "joinStep": "APPROVE_LOAN" }
    ],
    "APPROVE_LOAN_FINISHED": [{ "next": "COMPLETED" }]
  }
}
```

---

## Example Flows (seeded on startup)

Four example flows are upserted into the database on every app startup. They always reflect the latest engine features. Use them to experiment without creating definitions manually.

### DEFAULT_FLOW

Simple two-step linear sequence.

```
STEP_1 → STEP_2 → COMPLETED
```

Start it with:
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "DEFAULT_FLOW", "initialData": {}}'
```

### LOAN_APPROVAL

Credit-score based approval with conditional branching.

```
                            ┌─ [creditScore > 700] → AUTO_APPROVE ─────┐
VALIDATE_CREDIT ────────────┤                                            ├→ DISBURSE_FUNDS → COMPLETED
                            └─ [default] → MANUAL_REVIEW ──[approved] ──┘
                                                        └─ [default] → SEND_REJECTION → COMPLETED
```

Start with a high credit score (auto-approve path):
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "LOAN_APPROVAL", "initialData": {"creditScore": 750}}'
```

Start with a low credit score (manual review path):
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "LOAN_APPROVAL", "initialData": {"creditScore": 500}}'
```

### ORDER_FULFILLMENT

E-commerce order processing with branching at two decision points.

```
VALIDATE_ORDER → RESERVE_INVENTORY ─[inventoryAvailable]─→ PROCESS_PAYMENT ─[paymentSuccess]─→ SHIP_ORDER → COMPLETED
                                   └─[default]────────────→ NOTIFY_OUT_OF_STOCK → COMPLETED
                                                                                └─[default]────→ NOTIFY_PAYMENT_FAILED → COMPLETED
```

Start with happy-path data:
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "ORDER_FULFILLMENT", "initialData": {"inventoryAvailable": true, "paymentSuccess": true}}'
```

### PARALLEL_FLOW

Demonstrates parallel fork / join. `PREPARE_APPLICATION` fans out to `VALIDATE_CREDIT` and `VERIFY_IDENTITY` simultaneously; the engine waits for both to complete before advancing to `APPROVE_LOAN`.

```
PREPARE_APPLICATION ─┬─→ VALIDATE_CREDIT ──┐
                      └─→ VERIFY_IDENTITY ──┴─→ APPROVE_LOAN → COMPLETED
```

```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "PARALLEL_FLOW", "initialData": {}}'
```

---

## API Reference

### Process flows

| Method | Path | Description |
|---|---|---|
| `POST` | `/start-flow` | Start a new process instance |
| `GET` | `/status/{id}` | Get the current state of a process instance |

**Start a flow:**
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "DEFAULT_FLOW", "initialData": {"userId": 42}}'
# → {"processId": "550e8400-..."}
```

**Check status:**
```bash
curl -s http://localhost:8080/status/550e8400-e29b-41d4-a716-446655440000
```

Possible `status` values: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED`.

---

### Management API (`/api`)

#### Process Definitions

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/definitions` | List all process definitions |
| `POST` | `/api/definitions` | Create a new process definition |
| `GET` | `/api/definitions/{name}` | Get a definition by name |
| `PUT` | `/api/definitions/{name}` | Update a definition |
| `DELETE` | `/api/definitions/{name}` | Delete a definition (fails if active instances exist) |

#### Process Instances

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/processes` | List instances, optionally filtered by `status` and `definitionName`, paginated |
| `POST` | `/api/processes/{id}/cancel` | Cancel a running or stalled instance |
| `POST` | `/api/processes/{id}/retry` | Retry a failed or stalled instance |
| `POST` | `/api/processes/{id}/advance` | Manually advance a stuck instance |
| `POST` | `/api/processes/{id}/signal` | Injects a named signal event into a `SUSPENDED` process, resuming it from its current gate step |

#### Metrics

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/metrics/summary` | Total, running, completed, failed, stalled, cancelled counts + success rate |

---

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|---|---|---|
| `edoe.orchestrator.step-timeout-minutes` | `30` | Minutes before a running step is marked `STALLED` |
| `edoe.orchestrator.stalled-check-interval-ms` | `60000` | How often the timeout scanner runs (ms) |
| `edoe.orchestrator.outbox-poll-interval-ms` | `1000` | How often the outbox poller runs (ms) |

---

## Project Structure

```
src/main/java/com/edoe/orchestrator/
├── config/
│   ├── DataInitializer.java       # Upserts example flows on every startup
│   ├── KafkaConsumerConfig.java   # DLQ error handler (3 retries → *.DLT)
│   └── KafkaTopicConfig.java      # Topic declarations
├── controller/
│   ├── GlobalExceptionHandler.java  # Maps exceptions to HTTP 404/409/400
│   ├── ManagementController.java    # /api/** management endpoints
│   └── ProcessController.java       # /start-flow and /status/{id}
├── dto/
│   ├── MetricsSummaryResponse.java
│   ├── OrchestratorMessage.java     # Kafka message envelope
│   ├── ProcessDefinitionRequest.java
│   ├── ProcessDefinitionResponse.java
│   ├── ProcessInstanceResponse.java
│   ├── StartFlowRequest.java
│   └── TransitionRule.java          # {condition, next} or {parallel, joinStep} branch rule
├── entity/
│   ├── OutboxEvent.java             # outbox_events table
│   ├── ProcessDefinition.java       # process_definitions table
│   ├── ProcessInstance.java         # process_instances table
│   └── ProcessStatus.java           # RUNNING/COMPLETED/FAILED/SUSPENDED/STALLED/CANCELLED
├── listener/
│   └── WorkerEventListener.java     # Consumes "worker-events" topic
├── repository/
│   ├── OutboxEventRepository.java
│   ├── ProcessDefinitionRepository.java
│   └── ProcessInstanceRepository.java
├── service/
│   ├── CommandPublisherService.java  # Kafka producer
│   ├── ManagementService.java        # CRUD for definitions; process ops; metrics
│   ├── OutboxPublisherService.java   # Scheduled outbox poller
│   ├── StepTimeoutService.java       # Scheduled stall detector
│   └── TransitionService.java        # State machine: evaluates SpEL branches, advances state
└── worker/
    ├── WorkerCommandListener.java      # Consumes "orchestrator-commands" topic
    ├── WorkerEventPublisherService.java
    ├── WorkerTask.java                 # Interface: implement to add a task handler
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
