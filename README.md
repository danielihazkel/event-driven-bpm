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
| Timer / Delay Steps | A transition rule can carry `delayMs`; the engine advances the current step but parks the process as `SCHEDULED` until a background timer fires and dispatches the command |
| Sub-Processes (Call Activities) | A transition rule can carry `callActivity`; the engine spawns a child process from another definition, parks the parent as `WAITING_FOR_CHILD`, and automatically resumes it (with merged context) once the child completes or fails |
| Multi-Instance (Scatter-Gather) | A transition rule can carry `multiInstanceVariable`; the engine reads that context key as a `List`, dispatches one indexed command per element (`STEP__MI__0`, `STEP__MI__1`, …), waits for all to report back, then gathers every result into `context.multiInstanceResults` before advancing to `joinStep` |

---

## Process Definitions

A process definition declares the workflow graph. Each event type maps to an ordered list of **transition rules**. Rule types:

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

### Delay rule (Timer / Delay Steps)

When a rule carries `delayMs`, the engine advances `currentStep` to `next` but sets `status=SCHEDULED` and records a `wakeAt` timestamp (`now + delayMs`). A background `TimerService` polls every few seconds, and once `wakeAt` has passed it dispatches the step command and resumes normal execution.

```json
{
  "PREPARE_REQUEST_FINISHED": [
    { "delayMs": 5000, "next": "PROCESS_REQUEST" }
  ]
}
```

`delayMs` can be combined with a `condition` to apply the delay conditionally. Cancelled processes in `SCHEDULED` status are supported.

### Multi-instance rule (Scatter-Gather)

When a rule carries `multiInstanceVariable`, the engine reads that context key as a `List` and dynamically fans out:

1. One indexed Kafka command is dispatched per element: `STEP__MI__0`, `STEP__MI__1`, …
2. Each command's payload includes the full shared context plus `__miItem` (the element) and `__miIndex` (its position).
3. Workers match commands by their base step name (the `__MI__N` suffix is stripped automatically).
4. As each indexed `STEP__MI__N_FINISHED` event arrives, outputs are accumulated; duplicates are ignored.
5. When the last instance reports, all per-instance outputs are placed in `context.multiInstanceResults` (a list) and the process advances to `joinStep`.

```json
{
  "RECEIVE_ORDERS_FINISHED": [
    { "multiInstanceVariable": "orderItems", "next": "PROCESS_ORDER", "joinStep": "SHIP_ORDERS" }
  ]
}
```

- `multiInstanceVariable`: context key holding the `List` (e.g. `"orderItems"`)
- `next`: base step name dispatched to workers for each element
- `joinStep`: step to advance to once all instances complete

If the collection is empty or absent, the engine skips the wait entirely and jumps directly to `joinStep` with `multiInstanceResults: []`.

### Call-activity rule (Sub-Processes)

When a rule carries `callActivity`, the engine spawns a **child process** from the named definition, sets the parent to `WAITING_FOR_CHILD`, and waits. When the child reaches `COMPLETED`, the engine:

1. Merges the child's final `context_data` back into the parent's context.
2. Resumes the parent at `next` (or directly at `COMPLETED` if `next` is omitted).

If the child fails, the failure propagates to the parent (triggering the parent's own compensation chain if configured).

```json
{
  "COLLECT_APPLICATION_FINISHED": [
    { "callActivity": "CREDIT_CHECK_SUB", "next": "MAKE_DECISION" }
  ]
}
```

`callActivity` can be combined with a `condition` so the sub-process is only invoked when the condition matches — the next unconditional branch acts as the bypass path.

Cancelling a parent in `WAITING_FOR_CHILD` cascades the cancellation to any active child processes.

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

Nine example flows are upserted into the database on every app startup. They always reflect the latest engine features. Use them to experiment without creating definitions manually.

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

Credit-score based approval with conditional branching and a human-in-the-loop suspend gate.

```
                            ┌─ [creditScore > 700] ──→ AUTO_APPROVE ──────────────────┐
VALIDATE_CREDIT ────────────┤                                                           ├→ DISBURSE_FUNDS → COMPLETED
                            └─ [default] → MANUAL_REVIEW (SUSPENDED — awaits signal)  ─┤
                                           POST /api/processes/{id}/signal             │
                                             {"event":"APPROVAL_GRANTED","data":{...}} │
                                               ├─ [approved == true] ─────────────────┘
                                               └─ [default] ──→ SEND_REJECTION → COMPLETED
```

Start with a high credit score (auto-approve path):
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "LOAN_APPROVAL", "initialData": {"creditScore": 750}}'
```

Start with a low credit score — process suspends at `MANUAL_REVIEW` until signalled:
```bash
# 1. Start the process
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "LOAN_APPROVAL", "initialData": {"creditScore": 500}}'
# → {"processId": "<id>"}  status=SUSPENDED at MANUAL_REVIEW

# 2. Loan officer approves
curl -s -X POST http://localhost:8080/api/processes/<id>/signal \
  -H "Content-Type: application/json" \
  -d '{"event": "APPROVAL_GRANTED", "data": {"approved": true}}'
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

### PAYMENT_SAGA

Demonstrates saga-style compensation / rollback. If any step emits a `_FAILED` event, the engine walks backwards through completed steps and dispatches mapped compensation commands.

```
RESERVE_INVENTORY → CHARGE_PAYMENT → SHIP_ORDER → COMPLETED
      ↓ on failure
REFUND_PAYMENT ← UNDO_RESERVE_INVENTORY   (compensation chain, in reverse)
```

```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "PAYMENT_SAGA", "initialData": {}}'
```

To trigger compensation, publish a `CHARGE_PAYMENT_FAILED` event to the `worker-events` Kafka topic with the correct `processId`.

### DELAY_FLOW

Demonstrates timer / delay steps. After `PREPARE_REQUEST` finishes, the process parks in `SCHEDULED` status for 3 seconds before `PROCESS_REQUEST` is dispatched.

```
PREPARE_REQUEST → (3 000 ms delay, status=SCHEDULED) → PROCESS_REQUEST → COMPLETED
```

```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "DELAY_FLOW", "initialData": {}}'
# → poll GET /status/{id} — you will observe status=SCHEDULED briefly, then RUNNING again
```

### CREDIT_CHECK_SUB

A reusable child definition invoked by `SUB_PROCESS_FLOW`. Can also be started standalone.

```
FETCH_CREDIT_REPORT → EVALUATE_SCORE → COMPLETED
```

### SUB_PROCESS_FLOW

Demonstrates sub-process / call activities. After `COLLECT_APPLICATION` finishes, the engine spawns a `CREDIT_CHECK_SUB` child process and parks the parent at `WAITING_FOR_CHILD`. Once the child completes its two steps, the parent automatically resumes at `MAKE_DECISION` with the child's context merged in.

```
COLLECT_APPLICATION → [spawns CREDIT_CHECK_SUB child, status=WAITING_FOR_CHILD]
                              ↓ child: FETCH_CREDIT_REPORT → EVALUATE_SCORE → COMPLETED
                      [parent resumes] → MAKE_DECISION → COMPLETED
```

```bash
# Start the parent flow
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "SUB_PROCESS_FLOW", "initialData": {}}'
# → {\"processId\": \"<parent-id>\"}  status=WAITING_FOR_CHILD after COLLECT_APPLICATION finishes
# A child process (CREDIT_CHECK_SUB) is automatically started — find it via GET /api/processes?definitionName=CREDIT_CHECK_SUB
```

### SCATTER_GATHER_FLOW

Demonstrates multi-instance scatter-gather. `RECEIVE_ORDERS` finishes and the engine reads `orderItems` from the process context. It dispatches one indexed command per element (`PROCESS_ORDER__MI__0`, `PROCESS_ORDER__MI__1`, …), parks the process in `MULTI_INSTANCE_WAIT`, and gathers every result into `context.multiInstanceResults` before advancing to `SHIP_ORDERS`.

```
RECEIVE_ORDERS ─┬─→ PROCESS_ORDER__MI__0 ──┐
                ├─→ PROCESS_ORDER__MI__1 ──┤─→ (gather) → SHIP_ORDERS → COMPLETED
                └─→ PROCESS_ORDER__MI__2 ──┘
                     (one per orderItems element)
```

```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "SCATTER_GATHER_FLOW", "initialData": {"orderItems": [{"sku": "WIDGET-A", "qty": 2}, {"sku": "WIDGET-B", "qty": 1}]}}'
# → workers consume PROCESS_ORDER__MI__0 and PROCESS_ORDER__MI__1 in parallel
# → after both finish, context.multiInstanceResults contains both outputs
# → SHIP_ORDERS is dispatched automatically
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

Possible `status` values: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED`, `SCHEDULED`, `WAITING_FOR_CHILD`.

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
| `POST` | `/api/processes/{id}/cancel` | Cancel a `RUNNING`, `STALLED`, `SUSPENDED`, `SCHEDULED`, or `WAITING_FOR_CHILD` instance (cascades to active child processes) |
| `POST` | `/api/processes/{id}/retry` | Retry a failed or stalled instance |
| `POST` | `/api/processes/{id}/advance` | Manually advance a stuck instance |
| `POST` | `/api/processes/{id}/wake` | Force-wake a `SCHEDULED` process, skipping the remaining timer delay |
| `POST` | `/api/processes/{id}/signal` | Injects a named signal event into a `SUSPENDED` process, resuming it from its current gate step |

#### Metrics

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/metrics/summary` | Total, running, completed, failed, stalled, cancelled, scheduled, waitingForChild counts + success rate |

---

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|---|---|---|
| `edoe.orchestrator.step-timeout-minutes` | `30` | Minutes before a running step is marked `STALLED` |
| `edoe.orchestrator.stalled-check-interval-ms` | `60000` | How often the timeout scanner runs (ms) |
| `edoe.orchestrator.outbox-poll-interval-ms` | `1000` | How often the outbox poller runs (ms) |
| `edoe.orchestrator.timer-poll-interval-ms` | `5000` | How often the timer wakeup scanner runs (ms) |

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
│   └── TransitionRule.java          # Branch rule: {condition, next}, {parallel, joinStep}, {suspend}, {delayMs, next}, or {callActivity, next}
├── entity/
│   ├── OutboxEvent.java             # outbox_events table
│   ├── ProcessDefinition.java       # process_definitions table
│   ├── ProcessInstance.java         # process_instances table
│   └── ProcessStatus.java           # RUNNING/COMPLETED/FAILED/SUSPENDED/STALLED/CANCELLED/SCHEDULED/WAITING_FOR_CHILD
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
│   ├── TimerService.java             # Scheduled timer wakeup — resumes SCHEDULED processes
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
