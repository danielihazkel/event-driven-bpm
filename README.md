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
| Process Versioning | `PUT /api/definitions/{name}` inserts a new version row instead of mutating the existing one. Each process instance stores the `definitionVersion` it started with; in-flight instances continue to evaluate transitions from their snapshot version, unaffected by later updates |
| Output Mapping (JSONPath) | A transition rule can carry `outputMapping` — a map of target context key → JSONPath expression. When set, only the mapped fields from the worker output are persisted to `context_data`; unmapped fields are discarded. SpEL conditions still evaluate against the full worker output, so conditions and mappings are fully independent |
| Audit Log (Event Sourcing) | Every state transition, command dispatch, and received event writes an immutable row to `process_audit_logs`. Each `STEP_TRANSITION` and `PROCESS_STARTED` entry includes a `contextSnapshot` so the full context at that point can be restored |
| Time-Travel / Replay | `POST /api/processes/{id}/replay?fromStep={step}` rewinds a stuck process to any historical step validated against the audit trail. The engine restores the context snapshot from that moment, clears all mid-flight state (parallel, timer, saga, multi-instance), and re-queues the step command via the Outbox |
| Native HTTP REST Step | A transition rule can carry `httpRequest` (url, method, headers, body — all SpEL-able); the engine executes the HTTP call inline, skipping Kafka entirely. The JSON response is merged into `context_data` and the process advances to `next`. HTTP errors trigger the failure / compensation path. |
| Webhook Subscriptions | Register HTTP POST listeners via `POST /api/webhooks`. When a process reaches a terminal state (`COMPLETED`, `FAILED`, `CANCELLED`), the engine fires asynchronous payloads to all matching subscriptions. Supports per-definition filtering, HMAC-SHA256 request signing (`X-Webhook-Signature`), 3-attempt retry with backoff, and full audit trail entries (`WEBHOOK_DISPATCHED` / `WEBHOOK_FAILED`). |
| Pluggable Native Step Executors (SPI) | New step execution backends can be added by implementing `NativeStepExecutor` and annotating the class `@Service`. The engine discovers all implementations via Spring's `List<NativeStepExecutor>` injection and routes each transition rule to the first executor whose `canHandle()` returns `true`. The built-in HTTP step is now `HttpNativeStepExecutor` — a thin adapter over `HttpStepExecutor`. Adding gRPC, database, or script step types requires zero changes to `TransitionService`. |
| Human Tasks (First-Class) | A transition rule can carry `humanTask` (with `taskName`, `signalEvent`, `formSchema`, and optional `assignee`). When matched, the engine suspends the process AND creates a `HumanTask` DB record. A separate task-inbox frontend discovers pending tasks via `GET /api/tasks`, renders the form from `formSchema`, and submits results via `POST /api/tasks/{id}/complete` — which atomically marks the task done and resumes the process. Cancelling a process auto-cancels its pending tasks. |
| Compensation Failure Management | When a compensation (rollback) step itself fails, the process moves to `COMPENSATION_FAILED` status instead of plain `FAILED`. An `AlertService` fires an emergency alert (routed to the `alerts` SLF4J logger). Operators remediate the DB and then call `POST /api/processes/{id}/acknowledge-compensation-failure` to transition the process to `CANCELLED`. |
| Distributed Scheduler Locking (ShedLock) | `StepTimeoutService`, `OutboxPublisherService`, and `TimerService` each hold a ShedLock on PostgreSQL before executing. Under horizontal scaling, only one replica runs each poller at a time, preventing duplicate outbox publishes and double-stalling. |
| Security & RBAC (OAuth2 / JWT) | All REST endpoints are secured with Spring Security OAuth2 Resource Server (HMAC-SHA256 JWT). `ROLE_VIEWER` may read definitions, process state, and metrics. `ROLE_ADMIN` is required for any write, cancel, or advance operation. Swagger UI exposes a bearer-auth "Authorize" button. A dev-only `GET /dev/token` endpoint issues test tokens when the `dev` profile is active. |

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

### Output mapping rule (JSONPath filtering)

By default, a worker's entire output is shallow-merged into `context_data`. Add `outputMapping` to any rule to extract only the fields you care about, discarding the rest:

```json
{
  "VALIDATE_CREDIT_FINISHED": [
    {
      "condition": "#creditScore > 700",
      "next": "AUTO_APPROVE",
      "outputMapping": {
        "creditScore":  "$.creditScore",
        "creditBureau": "$.meta.bureau"
      }
    },
    { "next": "MANUAL_REVIEW", "suspend": true }
  ]
}
```

- Keys in `outputMapping` are the target variable names written to `context_data`.
- Values are [JSONPath](https://goessner.net/articles/JsonPath/) expressions evaluated against the raw worker output.
- If a path is missing from the worker output, the key is silently omitted (no error).
- SpEL conditions (e.g. `#creditScore > 700`) are evaluated against the **full** worker output before the mapping is applied, so conditions can reference any worker field regardless of whether it appears in `outputMapping`.
- When `outputMapping` is omitted, the existing full shallow-merge behaviour is preserved (backward-compatible).

### HTTP step rule (Native HTTP call)

When a rule carries `httpRequest`, the engine executes an HTTP call **inline** — no Kafka command is dispatched. The JSON response is merged into `context_data` and the process advances to `next`. On any HTTP error (4xx / 5xx / network failure) the failure / compensation path is triggered exactly like a `*_FAILED` event.

```json
{
  "STEP_1_FINISHED": [
    {
      "httpRequest": {
        "url":    "https://api.example.com/data",
        "method": "GET",
        "headers": { "Authorization": "'Bearer ' + #apiToken" }
      },
      "next": "STEP_2"
    }
  ]
}
```

SpEL is supported in `url`, header values, and `body`. Strings that contain `#` or `T(` are evaluated as SpEL expressions against the current process context; all other strings are used verbatim.

| Field | Required | Description |
|---|---|---|
| `url` | yes | Target URL. Supports SpEL. |
| `method` | yes | HTTP method: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. |
| `headers` | no | Map of header name → value (values support SpEL). |
| `body` | no | Request body string. Supports SpEL. Omit for GET requests. |

The HTTP response body is parsed as a JSON object and merged into `context_data`. Non-JSON or non-object responses are placed under `context.httpResponse`. A `httpStatusCode` key is always added to the merged output.

`httpRequest` can be combined with `condition` so the HTTP call is only made when the condition matches — subsequent unconditional branches act as the bypass path.

### Human task rule (First-Class Human Tasks)

When a rule carries `humanTask`, the engine suspends the process at `next` **and** creates a `HumanTask` record in the database, so a task-inbox frontend can discover, render, and complete it without polling `SUSPENDED` processes:

```json
{
  "VALIDATE_CREDIT_FINISHED": [
    { "condition": "#creditScore > 700", "next": "AUTO_APPROVE" },
    {
      "humanTask": {
        "taskName": "Manual Loan Review",
        "signalEvent": "APPROVAL_GRANTED",
        "formSchema": {
          "fields": [
            { "name": "approved", "type": "boolean", "label": "Approve?" },
            { "name": "notes",    "type": "string",  "label": "Notes" }
          ]
        }
      },
      "next": "MANUAL_REVIEW"
    }
  ]
}
```

The `signalEvent` value **must** also exist as a key in the `transitions` map — it is the signal that resumes the process after task completion.

| Field | Required | Description |
|---|---|---|
| `taskName` | yes | Human-readable name shown in the task list. |
| `signalEvent` | yes | The event key the engine fires when the task is completed. Must be a `transitions` key. |
| `formSchema` | no | Arbitrary JSON object that the frontend uses to render the task form dynamically. |
| `assignee` | no | Literal string or SpEL expression (starts with `#`) resolved against the process context. |

**Task lifecycle:**
```
humanTask rule matched
  → process status = SUSPENDED, currentStep = <next>
  → HumanTask record created (status = PENDING)
     → frontend: GET /api/tasks?status=PENDING
     → frontend: POST /api/tasks/{id}/complete  {"resultData": {"approved": true}}
        → task status = COMPLETED, resultData stored
        → engine: POST /api/processes/{pid}/signal automatically called
           → process resumes normally via transitions["APPROVAL_GRANTED"]
```

Cancelling a process in any status also cancels all its PENDING human tasks.

### Pluggable Native Step Executors (SPI)

The engine uses a **Spring plugin pattern** (`NativeStepExecutor` SPI) to dispatch non-Kafka steps. When a transition rule is matched, `TransitionService` streams over all registered `NativeStepExecutor` beans and picks the first whose `canHandle(rule)` returns `true`. If none matches, the step is dispatched via the normal Kafka outbox path.

To add a new native step type, implement the interface and annotate it `@Service`:

```java
@Service
@RequiredArgsConstructor
public class MyDatabaseStepExecutor implements NativeStepExecutor {

    @Override
    public boolean canHandle(TransitionRule rule) {
        // return true when this executor owns the rule
        return rule.myDbConfig() != null;
    }

    @Override
    public NativeStepResult execute(String stepName, TransitionRule rule, Map<String, Object> context) {
        // execute the step — do NOT modify ProcessInstance here
        // return NativeStepResult(true, outputMap) on success
        // return NativeStepResult(false, Map.of("error", "...")) on failure
    }
}
```

Spring picks up the new bean automatically; no changes to `TransitionService` or any other engine class are required. The built-in `HttpNativeStepExecutor` follows this exact pattern.

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

Eleven example flows are upserted into the database on every app startup. They always reflect the latest engine features. Use them to experiment without creating definitions manually.

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

Credit-score based approval with conditional branching, output mapping, and a **first-class human task** gate.

```
                            ┌─ [creditScore > 700] ──→ AUTO_APPROVE ───────────────────────────┐
VALIDATE_CREDIT ────────────┤                                                                    ├→ DISBURSE_FUNDS → COMPLETED
                            └─ [default] → MANUAL_REVIEW (SUSPENDED — HumanTask created)       ─┤
                                           POST /api/tasks/{taskId}/complete                   │
                                             {"resultData":{"approved":true,"notes":"LGTM"}}   │
                                               ├─ [approved == true] ──────────────────────────┘
                                               └─ [default] ──→ SEND_REJECTION → COMPLETED
```

Start with a high credit score (auto-approve path):
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "LOAN_APPROVAL", "initialData": {"creditScore": 750}}'
```

Start with a low credit score — process suspends at `MANUAL_REVIEW` and a HumanTask is created:
```bash
# 1. Start the process
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "LOAN_APPROVAL", "initialData": {"creditScore": 500}}'
# → {"processId": "<id>"}  status=SUSPENDED at MANUAL_REVIEW

# 2. Discover the pending task
curl -s http://localhost:8080/api/tasks?status=PENDING
# → [{id: "<taskId>", taskName: "Manual Loan Review", formSchema: {...}, ...}]

# 3. Complete the task (loan officer submits the form)
curl -s -X POST http://localhost:8080/api/tasks/<taskId>/complete \
  -H "Content-Type: application/json" \
  -d '{"resultData": {"approved": true, "notes": "Credit history looks good"}}'
# → process resumes automatically → DISBURSE_FUNDS → COMPLETED
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

### HTTP_STEP_FLOW

Demonstrates native HTTP step execution. After `STEP_1` finishes (handled by the built-in worker), the engine calls `https://jsonplaceholder.typicode.com/todos/1` (a public test API) **inline** — no Kafka command is dispatched. The JSON response is merged into `context_data`, then `STEP_2` is dispatched normally via the Outbox.

```
STEP_1 → (HTTP GET jsonplaceholder.typicode.com/todos/1 — inline, no Kafka) → STEP_2 → COMPLETED
```

```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "HTTP_STEP_FLOW", "initialData": {}}'
# → After STEP_1 finishes, context_data will contain the todo item fields (id, title, completed, userId)
# → from the HTTP response, merged automatically before STEP_2 is dispatched
```

### HUMAN_TASK_FLOW

Demonstrates the first-class human task gate. After `SUBMIT` finishes, the engine suspends the process and creates a `HumanTask` record. The task-inbox frontend retrieves it, renders the form, and submits the result — which automatically resumes the process.

```
SUBMIT → REVIEW_TASK (SUSPENDED — HumanTask created) → COMPLETE → COMPLETED
```

```bash
# 1. Start the flow
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "HUMAN_TASK_FLOW", "initialData": {"submitter": "alice"}}'
# → {"processId": "<id>"}  status=SUSPENDED at REVIEW_TASK

# 2. List pending tasks
curl -s http://localhost:8080/api/tasks?status=PENDING
# → [{id: "<taskId>", taskName: "Review Submission", formSchema: {...}}]

# 3. Complete the task
curl -s -X POST http://localhost:8080/api/tasks/<taskId>/complete \
  -H "Content-Type: application/json" \
  -d '{"resultData": {"approved": true, "comment": "LGTM"}}'
# → task COMPLETED, process resumes to COMPLETE → COMPLETED
```

---

## Audit Trail & Replay

Every process records a full audit trail. Use the audit endpoint to inspect it, then replay any historical step to recover stuck processes without starting over:

```bash
# 1. View the audit trail
curl -s http://localhost:8080/api/processes/<id>/audit

# 2. Replay from a specific historical step (resets status to RUNNING, restores context snapshot)
curl -s -X POST "http://localhost:8080/api/processes/<id>/replay?fromStep=VALIDATE_CREDIT"
# → {status: "RUNNING", currentStep: "VALIDATE_CREDIT", ...}
```

`replay` works on processes in any non-terminal status (`FAILED`, `STALLED`, `RUNNING`, `SUSPENDED`, `SCHEDULED`, `CANCELLED`). The engine:

1. Walks the audit trail backwards to find the last `PROCESS_STARTED` or `STEP_TRANSITION` entry for the requested step.
2. Restores the `contextSnapshot` captured at that point (falls back to current context if no snapshot is present).
3. Clears all mid-flight state: parallel branches, timer wakeup, saga compensation, multi-instance tracking.
4. Sets `status = RUNNING` and re-queues the step command via the Outbox.
5. Records a `PROCESS_REPLAYED` audit entry.

---

## API Reference

### Process flows

| Method | Path | Description |
|---|---|---|
| `POST` | `/start-flow` | Start a new process instance |
| `GET` | `/status/{id}` | Get the current state of a process instance |

**Start a flow (uses latest version by default):**
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "DEFAULT_FLOW", "initialData": {"userId": 42}}'
# → {"processId": "550e8400-..."}
```

**Start a flow pinned to a specific version:**
```bash
curl -s -X POST http://localhost:8080/start-flow \
  -H "Content-Type: application/json" \
  -d '{"definitionName": "DEFAULT_FLOW", "definitionVersion": 2, "initialData": {"userId": 42}}'
```

**Check status:**
```bash
curl -s http://localhost:8080/status/550e8400-e29b-41d4-a716-446655440000
```

Possible `status` values: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED`, `SCHEDULED`, `WAITING_FOR_CHILD`, `COMPENSATION_FAILED`.

---

### Management API (`/api`)

#### Process Definitions

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/definitions` | List the latest version of all process definitions |
| `POST` | `/api/definitions` | Create a new process definition (always starts at version 1) |
| `GET` | `/api/definitions/{name}` | Get the latest version of a definition by name |
| `PUT` | `/api/definitions/{name}` | Publish a new version of a definition (version N+1 inserted; existing instances keep running on their snapshot version) |
| `DELETE` | `/api/definitions/{name}` | Delete all versions of a definition (fails if active instances exist) |

#### Process Instances

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/processes` | List instances, optionally filtered by `status` and `definitionName`, paginated |
| `POST` | `/api/processes/{id}/cancel` | Cancel a `RUNNING`, `STALLED`, `SUSPENDED`, `SCHEDULED`, or `WAITING_FOR_CHILD` instance (cascades to active child processes) |
| `POST` | `/api/processes/{id}/retry` | Retry a failed or stalled instance |
| `POST` | `/api/processes/{id}/advance` | Manually advance a stuck instance |
| `POST` | `/api/processes/{id}/wake` | Force-wake a `SCHEDULED` process, skipping the remaining timer delay |
| `POST` | `/api/processes/{id}/signal` | Injects a named signal event into a `SUSPENDED` process, resuming it from its current gate step |
| `GET` | `/api/processes/{id}/audit` | Returns the ordered audit trail (all state transitions, commands, and events) for a process instance |
| `POST` | `/api/processes/{id}/replay` | Rewinds the process to a historical step (validated against the audit trail) and re-queues its command; query param `fromStep` required |
| `POST` | `/api/processes/{id}/acknowledge-compensation-failure` | Transitions a `COMPENSATION_FAILED` process to `CANCELLED` after manual DB remediation |

#### Human Tasks

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/tasks` | List human tasks. Optional: `?status=PENDING`, `?assignee=john` |
| `GET` | `/api/tasks/{id}` | Get a single task by ID |
| `POST` | `/api/tasks/{id}/complete` | Submit task result and auto-resume the suspended process |
| `POST` | `/api/tasks/{id}/cancel` | Cancel a task without signalling the process |

**Task completion flow:**
```bash
# Submit the form result — process resumes automatically
curl -s -X POST http://localhost:8080/api/tasks/<taskId>/complete \
  -H "Content-Type: application/json" \
  -d '{"resultData": {"approved": true, "notes": "Looks good"}}'
```

Response (`HumanTaskResponse`):
```json
{
  "id": "550e8400-...",
  "processInstanceId": "123e4567-...",
  "processDefinitionName": "LOAN_APPROVAL",
  "taskName": "Manual Loan Review",
  "signalEvent": "APPROVAL_GRANTED",
  "formSchema": { "fields": [{ "name": "approved", "type": "boolean", "label": "Approve?" }] },
  "assignee": null,
  "status": "COMPLETED",
  "createdAt": "2026-03-11T09:10:03",
  "completedAt": "2026-03-11T09:15:42",
  "resultData": { "approved": true, "notes": "Looks good" }
}
```

#### Webhook Subscriptions

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/webhooks` | Create a new webhook subscription |
| `GET` | `/api/webhooks` | List all subscriptions (paginated) |
| `GET` | `/api/webhooks/{id}` | Get a subscription by ID |
| `DELETE` | `/api/webhooks/{id}` | Delete a subscription |
| `PATCH` | `/api/webhooks/{id}/toggle` | Toggle a subscription active/inactive |

**Create a webhook subscription:**
```bash
curl -s -X POST http://localhost:8080/api/webhooks \
  -H "Content-Type: application/json" \
  -d '{
    "targetUrl": "https://your-endpoint.example.com/hook",
    "events": ["COMPLETED", "FAILED"],
    "processDefinitionName": "LOAN_APPROVAL",
    "secret": "my-signing-secret"
  }'
```

- `targetUrl` — Required. The URL to POST to on terminal state change.
- `events` — Required. Non-empty subset of `["COMPLETED", "FAILED", "CANCELLED"]`.
- `processDefinitionName` — Optional. If omitted the subscription matches all definitions.
- `secret` — Optional. When set, each request includes an `X-Webhook-Signature: sha256=<hmac>` header so the receiver can verify authenticity.

**Payload sent to the target URL:**
```json
{
  "processId": "550e8400-...",
  "definitionName": "LOAN_APPROVAL",
  "status": "COMPLETED",
  "contextData": "{ ... }",
  "completedAt": "2026-03-11T09:30:00",
  "timestamp": "2026-03-11T09:30:00.123"
}
```

The engine retries up to 3 times (backoffs: 0 ms, 1 000 ms, 3 000 ms). Success and permanent failure are both recorded in the process audit trail as `WEBHOOK_DISPATCHED` / `WEBHOOK_FAILED` events.

#### Metrics

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/metrics/summary` | Total, running, completed, failed, compensationFailed, stalled, cancelled, scheduled, waitingForChild counts + success rate |

---

## Security

All endpoints require a valid JWT Bearer token (HMAC-SHA256 / HS256).

| Role | Permissions |
|---|---|
| `ROLE_VIEWER` | `GET /api/**`, `GET /status/**` |
| `ROLE_ADMIN` | Everything — `POST /start-flow`, all `POST/PUT/DELETE /api/**` |

Swagger UI (`/swagger-ui.html`) has an **Authorize** button. Enter `Bearer <token>` to authenticate.

### Obtaining a test token (dev profile only)

```bash
# Start with dev profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Get an admin token
curl -s "http://localhost:8080/dev/token?role=ROLE_ADMIN"
# → {"token": "<jwt>"}

# Use it
curl -s http://localhost:8080/api/definitions \
  -H "Authorization: Bearer <jwt>"
```

Override the signing secret via the `JWT_SECRET` environment variable before deploying.

---

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|---|---|---|
| `edoe.orchestrator.step-timeout-minutes` | `30` | Minutes before a running step is marked `STALLED` |
| `edoe.orchestrator.stalled-check-interval-ms` | `60000` | How often the timeout scanner runs (ms) |
| `edoe.orchestrator.outbox-poll-interval-ms` | `1000` | How often the outbox poller runs (ms) |
| `edoe.orchestrator.timer-poll-interval-ms` | `5000` | How often the timer wakeup scanner runs (ms) |
| `edoe.orchestrator.jwt.secret` | base64-encoded dev key | HMAC-SHA256 secret for JWT verification (override via `JWT_SECRET` env var) |

---

## Project Structure

```
src/main/java/com/edoe/orchestrator/
├── config/
│   ├── AsyncConfig.java           # @EnableAsync thread pool for webhook dispatch (4/16/200)
│   ├── DataInitializer.java       # Upserts example flows on every startup
│   ├── HttpClientConfig.java      # RestTemplate bean (10s connect / 30s read)
│   ├── KafkaConsumerConfig.java   # DLQ error handler (3 retries → *.DLT)
│   ├── KafkaTopicConfig.java      # Topic declarations
│   ├── OpenApiConfig.java         # Swagger bearer-auth SecurityScheme + global SecurityRequirement
│   ├── SecurityConfig.java        # Spring Security filter chain: stateless JWT, RBAC rules
│   └── ShedLockConfig.java        # ShedLock LockProvider (JdbcTemplateLockProvider, DB-clock)
├── controller/
│   ├── GlobalExceptionHandler.java  # Maps exceptions to HTTP 404/409/400
│   ├── HumanTaskController.java     # /api/tasks — list, get, complete, cancel
│   ├── ManagementController.java    # /api/** management endpoints
│   ├── ProcessController.java       # /start-flow and /status/{id}
│   ├── TokenController.java         # GET /dev/token — dev-profile test JWT issuer
│   └── WebhookController.java       # /api/webhooks CRUD + toggle
├── dto/
│   ├── AuditLogResponse.java
│   ├── CompleteTaskRequest.java     # Body for POST /api/tasks/{id}/complete
│   ├── HttpRequestConfig.java       # HTTP step config: url, method, headers, body (all SpEL-able)
│   ├── HumanTaskDefinition.java     # Inline task spec in TransitionRule: taskName, signalEvent, formSchema, assignee
│   ├── HumanTaskResponse.java       # Response DTO for human task endpoints
│   ├── MetricsSummaryResponse.java
│   ├── OrchestratorMessage.java     # Kafka message envelope
│   ├── ProcessDefinitionRequest.java
│   ├── ProcessDefinitionResponse.java
│   ├── ProcessInstanceResponse.java
│   ├── StartFlowRequest.java
│   ├── TransitionRule.java          # 11-component record: condition/next/parallel/joinStep/suspend/delayMs/callActivity/multiInstanceVariable/outputMapping/httpRequest/humanTask
│   ├── WebhookSubscriptionRequest.java
│   └── WebhookSubscriptionResponse.java
├── entity/
│   ├── AuditEventType.java          # 35-constant enum (includes COMPENSATION_FAILED/ACKNOWLEDGED, HUMAN_TASK_*)
│   ├── HumanTask.java               # human_tasks table
│   ├── HumanTaskStatus.java         # PENDING / COMPLETED / CANCELLED
│   ├── OutboxEvent.java             # outbox_events table
│   ├── ProcessAuditLog.java         # process_audit_logs table (immutable, append-only)
│   ├── ProcessDefinition.java       # process_definitions table
│   ├── ProcessInstance.java         # process_instances table
│   ├── ProcessStatus.java           # RUNNING/COMPLETED/FAILED/SUSPENDED/STALLED/CANCELLED/SCHEDULED/WAITING_FOR_CHILD/COMPENSATION_FAILED
│   └── WebhookSubscription.java     # webhook_subscriptions table
├── listener/
│   └── WorkerEventListener.java     # Consumes "worker-events" topic
├── repository/
│   ├── AuditLogRepository.java
│   ├── HumanTaskRepository.java     # findByStatus, findByProcessInstanceId, findByAssigneeAndStatus
│   ├── OutboxEventRepository.java
│   ├── ProcessDefinitionRepository.java
│   ├── ProcessInstanceRepository.java
│   └── WebhookSubscriptionRepository.java
├── service/
│   ├── AlertService.java             # Interface: compensationFailed(processId, step, reason)
│   ├── AuditLogService.java          # Records and retrieves immutable audit entries
│   ├── CommandPublisherService.java  # Kafka producer
│   ├── HttpStepExecutor.java         # Executes inline HTTP calls for httpRequest rules (SpEL, RestTemplate)
│   ├── HumanTaskService.java         # createTask, listTasks, getTask, completeTask, cancelTask, cancelTasksForProcess
│   ├── ManagementService.java        # CRUD for definitions; process ops; metrics; replay; acknowledgeCompensationFailure
│   ├── OutboxPublisherService.java   # Scheduled outbox poller (@SchedulerLock: publishPendingEvents)
│   ├── Slf4jAlertService.java        # AlertService impl — logs to "alerts" SLF4J logger at ERROR
│   ├── StepTimeoutService.java       # Scheduled stall detector (@SchedulerLock: detectStalledProcesses)
│   ├── TimerService.java             # Scheduled timer wakeup (@SchedulerLock: wakeExpiredTimers)
│   ├── TransitionService.java        # State machine: evaluates SpEL branches, advances state
│   ├── WebhookDispatchService.java   # @Async fire-and-forget webhook dispatcher (retry + HMAC)
│   └── WebhookService.java           # CRUD for webhook subscriptions
├── spi/
│   ├── NativeStepExecutor.java       # SPI interface: canHandle(rule) + execute(stepName, rule, context)
│   ├── NativeStepResult.java         # Result record: success + output map
│   └── HttpNativeStepExecutor.java   # Built-in adapter: wraps HttpStepExecutor, handles httpRequest rules
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
