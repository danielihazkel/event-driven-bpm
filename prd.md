This PRD covers the creation of a **Custom Event-Driven Orchestration Engine**. We will move away from specific business logic (like "Email") and focus on building the **platform** that allows you to plug in any task.

---

# PRD: Generic Event-Driven Orchestration Engine (EDOE)

## 1. Objective

To build a lightweight, Java-based orchestration system that manages long-running processes using an event-driven "Choreography" pattern. The system must ensure that tasks are executed in a specific order, handle failures gracefully, and maintain a persistent record of all process states.

## 2. System Components

1. **The Orchestrator:** The "Brain" that manages the state machine and transitions.
2. **The State Store:** A relational database (PostgreSQL) to track process instances.
3. **The Event Bus:** Kafka topics for `Commands` (outbound) and `Events` (inbound).
4. **The Worker (Template):** A generic interface for services to consume and finish tasks.

---

## 3. Functional Requirements

* **FR1: Process Definition:** Ability to define a workflow graph of steps via a REST API or database seed.
* **FR2: Correlation:** Every message must carry a `processId` to link events back to the correct instance.
* **FR3: Idempotency:** The engine must ignore duplicate "Finish" events for the same step.
* **FR4: Persistence:** Every state transition must be written to the DB before the next command is sent.
* **FR5: Error Handling:** Ability to detect a "Failed" event and stop the process or trigger a rollback.
* **FR6: Management API:** CRUD operations for process definitions; ability to cancel, retry, and advance process instances; aggregate metrics.
* **FR7: Conditional Branching:** Transition targets must be resolvable at runtime based on the accumulated process context, using SpEL expressions evaluated against context variables.

---

## 4. Technical Architecture (Generic)

### Data Schema

#### `process_instances`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Primary Key (Process Instance ID) |
| `definition_name` | String | Name of the flow (e.g., `LOAN_APPROVAL`) |
| `current_step` | String | Current active task |
| `context_data` | TEXT (JSON) | The "Input/Output" payload accumulated across all steps |
| `status` | Enum | `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED` |
| `created_at` | Timestamp | When the process was started |
| `step_started_at` | Timestamp | When the current step was entered |
| `completed_at` | Timestamp (nullable) | When the process reached a terminal state |

#### `process_definitions`

| Field | Type | Description |
| --- | --- | --- |
| `id` | Long (IDENTITY) | Primary Key |
| `name` | String (UNIQUE) | Logical name of the flow |
| `initial_step` | String | The first step to execute |
| `transitions_json` | TEXT (JSON) | Serialised `Map<event, List<TransitionRule>>` |
| `created_at` | Timestamp | — |
| `updated_at` | Timestamp | — |

#### `outbox_events`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | — |
| `aggregate_id` | String | Process instance ID |
| `event_type` | String | The Kafka command type (step name) |
| `payload` | TEXT | Serialised context snapshot |
| `published` | Boolean | `false` until the outbox poller sends it |
| `created_at` | Timestamp | — |

### Transition Rule Format

Each event in `transitions_json` maps to an ordered list of `{ condition, next }` objects. Branches are evaluated top-to-bottom; the first branch whose condition matches is taken. A null or blank condition is unconditional (always matches).

```json
"VALIDATE_CREDIT_FINISHED": [
  { "condition": "#creditScore > 700", "next": "AUTO_APPROVE" },
  { "next": "MANUAL_REVIEW" }
]
```

Conditions use **Spring Expression Language (SpEL)** and are evaluated against the merged process `context_data`. Each context key is available as `#keyName`.

---

## 5. Implementation To-Do List

### Phase 1: Infrastructure & Setup ✅

* [x] **Docker Setup:** Create `docker-compose.yml` with Kafka (KRaft) and PostgreSQL.
* [x] **Spring Project:** Initialize Spring Boot with `Spring Kafka` and `Spring Data JPA`.
* [x] **Topic Creation:** Define two main topics: `orchestrator-commands` and `worker-events`.

### Phase 2: The Core Engine (Orchestrator) ✅

* [x] **State Entity:** Create the `ProcessInstance` JPA entity.
* [x] **Transition Logic:** Write a `TransitionService` that takes a `ProcessID + Event` and determines the next `Command`.
* [x] **Event Consumer:** Implement a Kafka Listener that waits for `WorkerFinishedEvent`.
* [x] **Command Producer:** Implement a service to push JSON envelopes to the `orchestrator-commands` topic.

### Phase 3: The Worker (Task Executor) ✅

* [x] **Worker Listener:** Create a generic listener that filters Kafka messages by "Task Type."
* [x] **Business Logic Placeholder:** A simple Java method that simulates work (e.g., `Thread.sleep(1000)`).
* [x] **Callback Logic:** The worker must wrap its result in an `Event` envelope and send it back.

### Phase 4: Reliability & Monitoring ✅

* [x] **Logging:** Implement Slf4j logging for every state change for "Audit Trails."
* [x] **Dead Letter Queue:** Configure a DLQ for failed Kafka messages (3 retries, 1 s apart, then `*.DLT`).
* [x] **API Endpoint:** Create a `GET /status/{id}` endpoint to query the process state.
* [x] **Transactional Outbox Pattern:** DB write and outbox entry in one transaction; a scheduler polls and publishes.
* [x] **Idempotency:** Duplicate `*_FINISHED` events for an already-advanced step are silently ignored.
* [x] **Correlation ID Header:** `processId` injected into Kafka record headers so workers don't have to parse the body.
* [x] **Step-Level Timeouts:** Scheduled scan of `process_instances` for steps stuck in `RUNNING` beyond N minutes; marks them `STALLED`.

### Phase 5: Management Backend ✅

* [x] **DB-Driven Definitions:** Move process definitions from in-memory config to a `process_definitions` table.
* [x] **Definition CRUD:** REST endpoints to create, read, update, and delete process definitions (`/api/definitions`).
* [x] **Admin Process Operations:** Endpoints to cancel, retry, and manually advance a process instance (`/api/processes/{id}/cancel|retry|advance`).
* [x] **Paginated Process Listing:** `GET /api/processes` with optional `status` and `definitionName` filters.
* [x] **Metrics Endpoint:** `GET /api/metrics/summary` — total, running, completed, failed, stalled, cancelled counts + success rate.
* [x] **Global Exception Handling:** `@RestControllerAdvice` mapping `NoSuchElementException` → 404, `IllegalStateException` → 409, generic → 400.
* [x] **OpenAPI / Swagger UI:** `springdoc-openapi` integration; all endpoints documented with `@Operation` and `@ApiResponse`.
* [x] **Example Flow Seeding:** `DataInitializer` upserts example definitions on every startup.

### Phase 6: Conditional Transitions ✅

* [x] **TransitionRule DTO:** New `{ condition, next }` record replacing the flat `Map<String, String>` transitions format.
* [x] **SpEL-Based Branch Evaluation:** `TransitionService.evaluateBranches()` iterates rules top-to-bottom, evaluates each condition against the merged process context using Spring Expression Language.
* [x] **Updated Definition API:** `ProcessDefinitionRequest` and `ProcessDefinitionResponse` updated to `Map<String, List<TransitionRule>>`.
* [x] **Updated Seeded Examples:** `DataInitializer` now seeds three flows that demonstrate conditional branching:
  - `DEFAULT_FLOW` — simple linear two-step chain.
  - `LOAN_APPROVAL` — credit-score gate → auto-approve or manual review → disburse or reject.
  - `ORDER_FULFILLMENT` — inventory check + payment gate with multiple terminal paths.

---

## 6. The "Generic" Flow Logic

1. **API** → Orchestrator: "Start User Flow"
2. **Orchestrator** → DB: Insert `status=RUNNING`, `step=<initialStep>`.
3. **Orchestrator** → Outbox: Queue first command.
4. **Outbox Poller** → Kafka: Publish `{ type: "<step>", pid: "...", data: {...} }` to `orchestrator-commands`.
5. **Worker** → Logic: Does the work; publishes `{ type: "<step>_FINISHED", pid: "...", output: {...} }` to `worker-events`.
6. **Orchestrator** → DB: Merge output into `context_data`; evaluate transition rules for `<step>_FINISHED`.
7. **Orchestrator** → DB: Update `current_step` to the matched branch's `next`.
8. **Repeat** until a rule maps to `COMPLETED`.

---

## 7. Architecture Diagram

```mermaid
graph TD
    %% External Entry
    Client[Client/API Gateway] -->|1. POST /start-flow| Orch[Orchestrator Service]

    %% Orchestrator Internals
    subgraph Orchestrator_Logic [The Brain]
        Orch -->|2. Save Initial State| DB[(PostgreSQL: State Store)]
        Orch -->|3. Queue Outbox Event| DB
        Outbox[Outbox Poller] -->|4. Publish Command| K_Cmd[Kafka: orchestrator-commands]
    end

    %% Event Bus Layer
    subgraph Message_Broker [Kafka Cluster]
        K_Cmd
        K_Evt[Kafka: worker-events]
        DLQ[*.DLT Dead Letter Topics]
    end

    %% Worker Layer
    subgraph Worker_Services [The Muscle]
        K_Cmd -->|5. Pull Task| W1[Worker A]
        K_Cmd -->|5. Pull Task| W2[Worker B]
        W1 -->|6. Logic Done| K_Evt
        W2 -->|6. Logic Done| K_Evt
    end

    %% Closing the Loop
    K_Evt -->|7. Consume Result| Orch
    Orch -->|8. Evaluate SpEL Branches| Orch
    Orch -->|9. Transition State| DB
    Orch -->|10. Queue Next Command| DB

    %% Error path
    K_Evt -->|Retry x3 then DLQ| DLQ

    %% Styling
    style Orch fill:#f96,stroke:#333,stroke-width:2px
    style DB fill:#ddd,stroke:#333
    style Message_Broker fill:#bbf,stroke:#333
```
