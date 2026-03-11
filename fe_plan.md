# Frontend Plan: Event-Driven Orchestration Engine (EDOE)

## 1. Overview
The goal is to build a modern, high-performance Single Page Application (SPA) to design, manage, and monitor workflows for the Event-Driven Orchestration Engine (EDOE). The frontend will interact with the extensive Management API and Process Execution endpoints exposed by the backend.

## 2. Technology Stack
- **Framework:** React + TypeScript (Strict Mode enabled for robust type-safety)
- **Build Tool:** Vite (For lightning-fast HMR and optimized production builds)
- **Styling:** Tailwind CSS (Utility-first CSS framework for rapid UI development)
- **Component Library:** shadcn/ui (Accessible, customizable Radix UI primitives)
- **Icons:** Lucide-React (Clean, consistent iconography)
- **Routing:** React Router v6 (For client-side routing, nested layouts, and loaders)
- **Data Fetching:** TanStack Query (React Query) (For state management, API caching, polling, and mutations)
- **Mocking/API Layer:** MSW (Mock Service Worker) or a custom API Adapter pattern to seamlessly switch between the real backend and local mock data if the backend is down.
- **Workflow Visualization:** React Flow (xyflow) (To visually render process definitions, nodes, conditional/parallel edges, and live execution paths)
- **Code/JSON Editor:** `@monaco-editor/react` (For advanced editing of `initialData`, `contextData`, and raw JSON transition rules with syntax highlighting)

## 3. Communication with the Backend (API Specifications & DTOs)

This section details exactly how the frontend must communicate with the backend services. The frontend should generate TypeScript interfaces for these models to ensure type safety.

### 3.1. Process Definitions (`/api/definitions`)

**Get All Definitions**
- **Endpoint:** `GET /api/definitions`
- **Response `ProcessDefinitionResponse[]`:**
  ```json
  [
    {
      "id": 1,
      "name": "LOAN_APPROVAL",
      "version": 1,
      "initialStep": "VALIDATE_CREDIT",
      "transitions": { ... },
      "compensations": { "RESERVE_INVENTORY": "UNDO_RESERVE_INVENTORY" },
      "createdAt": "2026-03-11T09:00:00Z",
      "updatedAt": "2026-03-11T09:00:00Z"
    }
  ]
  ```

**Get Single Definition**
- **Endpoint:** `GET /api/definitions/{name}`
- **Response:** Single `ProcessDefinitionResponse` object.

**Create/Update Definition**
- **Endpoint:** `POST /api/definitions` (Create) or `PUT /api/definitions/{name}` (Update Version)

**Delete Definition**
- **Endpoint:** `DELETE /api/definitions/{name}`
- **Response:** `204 No Content`
- **Payload `ProcessDefinitionRequest`:**
  ```json
  {
    "name": "LOAN_APPROVAL",
    "initialStep": "VALIDATE_CREDIT",
    "transitions": {
      "VALIDATE_CREDIT_FINISHED": [
        { "condition": "#creditScore > 700", "next": "AUTO_APPROVE" },
        { "next": "MANUAL_REVIEW", "suspend": true }
      ]
    },
    "compensations": {
      "STEP_X": "UNDO_STEP_X"
    }
  }
  ```
- **Response:** Returns the saved `ProcessDefinitionResponse` (with the assigned `version`).

*(Note: The `transitions` object values are arrays of `TransitionRule` which can contain `condition`, `next`, `parallel`, `joinStep`, `suspend`, `delayMs`, `callActivity`, `multiInstanceVariable`, `outputMapping`, or `httpRequest`).*

### 3.2. Process Execution & Instances (`/api/processes` & `/start-flow`)

**Start a Flow**
- **Endpoint:** `POST /start-flow`
- **Payload `StartFlowRequest`:**
  ```json
  {
    "definitionName": "DEFAULT_FLOW",
    "definitionVersion": 1, 
    "initialData": { "userId": 42 } 
  }
  ```
  *(Note: `definitionVersion` is optional. If omitted, the latest version is used.)*
- **Response:**
  ```json
  { "processId": "550e8400-e29b-41d4-a716-446655440000" }
  ```

**List Processes**
- **Endpoint:** `GET /api/processes?status={status}&definitionName={name}&page=0&size=20&sort=createdAt,desc`
- **Query Parameters (all optional):**
  - `status` — One of: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED`, `SCHEDULED`, `WAITING_FOR_CHILD`
  - `definitionName` — Filter by definition name (e.g., `LOAN_APPROVAL`)
  - `page` — Zero-indexed page number (default `0`)
  - `size` — Page size (default `20`)
  - `sort` — Sort field and direction (e.g., `createdAt,desc`, `status,asc`)
- **Response (Spring `Page<T>` shape):**
  ```json
  {
    "content": [
      {
        "id": "550e8400-...",
        "definitionName": "DEFAULT_FLOW",
        "definitionVersion": 1,
        "currentStep": "STEP_1",
        "status": "RUNNING",
        "contextData": "{\"userId\": 42}",
        "createdAt": "2026-03-11T09:01:00",
        "stepStartedAt": "2026-03-11T09:01:00",
        "completedAt": null,
        "parentProcessId": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 150,
    "totalPages": 8,
    "last": false,
    "number": 0,
    "size": 20,
    "numberOfElements": 20,
    "first": true,
    "empty": false,
    "sort": { "empty": false, "unsorted": false, "sorted": true }
  }
  ```

**Get Process Status (lightweight)**
- **Endpoint:** `GET /status/{id}`
- **Note:** This is a lightweight endpoint. Unlike `/api/processes`, the `context` field here is returned as a **parsed JSON object** (not a string). Use this for quick polling; use the full `/api/processes` listing for table views.
- **Response:**
  ```json
  {
    "processId": "550e8400-...",
    "status": "RUNNING",
    "step": "STEP_1",
    "context": { "userId": 42 }
  }
  ```

**Get Audit Trail**
- **Endpoint:** `GET /api/processes/{id}/audit`
- **Response `AuditLogResponse[]`:**
  ```json
  [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "processId": "123e4567-e89b-12d3-a456-426614174000",
      "eventType": "PROCESS_STARTED",
      "stepName": "VALIDATE_CREDIT",
      "fromStatus": null,
      "toStatus": "RUNNING",
      "payload": "{\"contextSnapshot\":{\"creditScore\":750}}",
      "occurredAt": "2026-03-11T09:00:00"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "processId": "123e4567-e89b-12d3-a456-426614174000",
      "eventType": "STEP_TRANSITION",
      "stepName": "AUTO_APPROVE",
      "fromStatus": "RUNNING",
      "toStatus": "RUNNING",
      "payload": "{\"contextSnapshot\":{\"creditScore\":750,\"validated\":true}}",
      "occurredAt": "2026-03-11T09:00:02"
    }
  ]
  ```
  *(Note: `payload` is a JSON string. For `PROCESS_STARTED` and `STEP_TRANSITION` events, it contains a `contextSnapshot` key — used by the Replay feature to restore state.)*

### 3.3. Advanced Process Administrative Actions

For all these, append to `/api/processes/{id}`.

- **Cancel:** `POST /cancel` - (No body) Returns `ProcessInstanceResponse`.
- **Retry:** `POST /retry` - (No body) Returns `ProcessInstanceResponse`.
- **Advance:** `POST /advance` - (No body) Returns `ProcessInstanceResponse`.
- **Force Wake (Scheduled):** `POST /wake` - (No body) Returns `ProcessInstanceResponse`.
- **Signal (Suspended):** `POST /signal`
  - **Payload `SignalRequest`:**
    ```json
    {
      "event": "APPROVAL_GRANTED",
      "data": { "approved": true, "reviewer": "admin" }
    }
    ```
- **Replay/Time Travel:** `POST /replay?fromStep={stepName}` - (No body) Returns `ProcessInstanceResponse`.

### 3.4. Metrics (`/api/metrics`)

- **Endpoint:** `GET /api/metrics/summary`
- **Response `MetricsSummaryResponse`:**
  ```json
  {
    "total": 100,
    "running": 5,
    "completed": 85,
    "failed": 2,
    "cancelled": 3,
    "suspended": 2,
    "stalled": 1,
    "scheduled": 2,
    "waitingForChild": 0,
    "successRate": 85.0
  }
  ```

### 3.5. Webhooks (`/api/webhooks`)

- **List:** `GET /api/webhooks?page=0&size=20` (Paginated)
- **Get Single:** `GET /api/webhooks/{id}`
- **Delete:** `DELETE /api/webhooks/{id}`
- **Toggle Active:** `PATCH /api/webhooks/{id}/toggle`
- **Create:** `POST /api/webhooks`
  - **Payload `WebhookSubscriptionRequest`:**
    ```json
    {
      "targetUrl": "https://api.example.com/webhook",
      "events": ["COMPLETED", "FAILED"],
      "processDefinitionName": "LOAN_APPROVAL", 
      "secret": "optional-hmac-secret"
    }
    ```
  - *(Note: `processDefinitionName` and `secret` are optional)*
  - **Response `WebhookSubscriptionResponse`:** (Echoes request data plus `id` and `active` boolean flag).

## 4. UI/UX Design System
- **Aesthetics:** Sleek, modern, dashboard-centric design. Support for both Dark Mode and Light Mode with seamless switching. Use of subtle glassmorphism for floating overlays, and vibrant but professional colors (e.g., slate/zinc backgrounds with indigo/violet primary accents to signify active states).
- **Layout Structure:** 
  - **Left Sidebar:** Collapsible navigation menu housing links to (Dashboard, Definitions, Instances, Webhooks, Settings).
  - **Top App Bar:** Breadcrumbs for navigational context, Global Search, Theme Toggle, and Environment/Connection Health Indicator.
  - **Main Content Area:** Scrollable container for page content with max-width constraints on wide screens for readability.
- **Interactions:** Subtle hover effects on interactive elements, smooth data transitions, skeleton loading states while React Query fetches data, and accessible toast notifications for API success/errors (e.g., Sonner).

## 5. Page Specifications & Layouts

### 5.1. Dashboard (`/`)
- **Purpose:** At-a-glance view of system health and quick navigational shortcuts.
- **Visuals & Components:**
  - **Metric Cards Grid:** Top row displaying total processes, running, completed, failed, stalled, and overall success rate (fetched from `/api/metrics/summary`).
  - **Quick Actions Bar:** "Start New Flow" and "Create Definition" prominent buttons.
  - **Recent Activity Table:** Top 5-10 most recently started or failed instances to alert the user of immediate issues.

### 5.2. Process Definitions (`/definitions`)
- **Purpose:** Manage, explore, and configure workflow schemas.
- **Visuals & Components:**
  - **Data Table:** List of definitions showing Name, Latest Version, Created At, and Actions (Edit, Delete, Start).
  - **Definition Editor View (`/definitions/:name`):**
    - **Header:** Definition name badge, Version selector (to view older versions), and Action buttons (Save New Version, Delete).
    - **Split Pane / Tabs:**
      - **Visual Mode (React Flow):** Canvas displaying step nodes and edges based on `transitions_json`. Custom nodes for Fork, Join, Suspend, HttpRequest, and Delay steps.
      - **JSON Mode (Monaco):** Editor to manually view or edit definition transition rules.

### 5.3. Process Instances / Monitoring (`/instances`)
- **Purpose:** Track real-time execution of processes across the system.
- **Visuals & Components:**
  - **Command Bar / Filters:** Dropdowns to filter by `definitionName` and `status` (RUNNING, FAILED, STALLED, SUSPENDED, SCHEDULED, WAITING_FOR_CHILD, etc.). Search by ID.
  - **Data Table:** ID, Definition, Status (colored badges), Current Step, Started At, and Completed At. Includes server-side pagination controls.

### 5.4. Process Detail View (`/instances/:id`)
- **Purpose:** Deep dive into a single process execution, troubleshooting, and manual administrative intervention.
- **Visuals & Components:**
  - **Header Ribbon:** Status badge, Process ID, timestamps. Administrative Action buttons: Cancel, Retry, Advance, Force Wake (if SCHEDULED), Signal (if SUSPENDED).
  - **Context Navigation:** If `parentProcessId` exists, show an "Up to Parent Process" clickable link. If the process is `WAITING_FOR_CHILD`, query and list active sub-processes.
  - **Split Pane Layout:**
    - **Left Side (Visual Tracker):** React Flow graph of the definition, with the `current_step` highlighted with a glowing pulse, completed steps marked green, and failed steps marked red. The parser logic must strip suffixes like `__MI__\d+` from the active step name to correctly highlight the base step during scatter-gather flows.
    - **Right Side (Tabs):**
      1. **Context/Data:** Read-only generic JSON viewer for `contextData`.
      2. **Audit Trail:** Vertical timeline (using Tailwind standard timeline UI) decoding events from `/api/processes/{id}/audit`. 
      3. **Advanced State:** View specialized database fields like `parallelPending`, `joinStep`, and `compensating` states.
  - **Intervention Modals:**
    - **Signal Modal:** Form requiring an Event Name and JSON data payload to resume a `SUSPENDED` process.
    - **Replay/Time-Travel Modal:** Modal that fetches the audit trail, presents a timeline of viable historical steps, and exposes a "Replay from Here" button to call `/api/processes/{id}/replay?fromStep=X`.

### 5.5. Webhooks Management (`/webhooks`)
- **Purpose:** Manage subscriptions to terminal state events for system integrations.
- **Visuals & Components:**
  - **Data Table:** Webhook URL, targeted events, definition filters, and an Active/Inactive toggle switch (`/api/webhooks/{id}/toggle`).
  - **Slide-out Panel / Modal:** Form to create a new subscription (target URL, multi-select for events, optional secret).

## 6. Flow Patterns, Task Types & Visualization Guide

To effectively build the workflow definitions and visualizer, the frontend must support the following orchestration patterns and task types.

### 6.1. Supported Task & Node Types
The `reactflow` implementation should include custom node components for the following natively supported step types:
- **Default Task (External Worker):** A standard step that dispatches a Kafka command and waits for a `_FINISHED` event.
  - *Visualization:* Standard rectangular node.
- **HTTP Task (`httpRequest`):** A step that makes an inline HTTP call (GET, POST, etc.) using SpEL evaluated variables.
  - *Visualization:* Node with an API/Globe icon or distinct color.
- **Timer/Delay Task (`delayMs`):** A step that parks the process for a specific duration before advancing.
  - *Visualization:* Node with a Clock/Timer icon.
- **Human Task (`suspend: true`):** A step that pauses the process waiting for a manual API signal to resume.
  - *Visualization:* Node indicating a "pause" or user action, styled uniquely (e.g., orange/yellow).
- **Sub-Process Task (`callActivity`):** Sparks a child process from another definition and waits for its completion.
  - *Visualization:* Node displaying a "layers" or "sub-flow" icon, indicating delegation to another flow.

### 6.2. Workflow Patterns & Routing Rules
The React Flow edge connections and node structures must support these complex routing patterns found in the `transitions` object:
- **Conditional Branching / XOR Gateway:** A single step's transition array with multiple `{ condition, next }` rules.
  - *Visualization:* A Diamond gateway node connected to the task. The edges connecting the diamond to subsequent steps must label the specific SpEL `condition` text. The first rule without a condition is the "Default" path.
- **Parallel Fork & Join (AND Gateway):** A rule using `{ parallel: ["A", "B"], joinStep: "C" }`.
  - *Visualization:* An outward Fork node spawning parallel edges to tasks A and B, converging onto an inward Join node before proceeding to C.
- **Multi-Instance / Scatter-Gather:** A rule using `{ multiInstanceVariable: "items", next: "PROCESS", joinStep: "GATHER" }`.
  - *Visualization:* A designated Multi-Instance node wrapper or icon on the `PROCESS` node (e.g., three horizontal stacked lines), indicating it dynamically fans out based on an array variable, converging at the `GATHER` step.
- **Saga Compensations (Rollbacks):** Definitions can include a `compensations` map (e.g., `"STEP_A" -> "UNDO_STEP_A"`).
  - *Visualization:* Secondary paths or dashed-line edges connecting a step to its compensation step, potentially triggered by a boundary error event icon.

## 7. Complete TypeScript DTO Reference

This section provides the exact TypeScript interfaces the frontend must implement. These map 1:1 to backend Java records.

### 7.1. Core DTOs

```typescript
// --- Enums ---

type ProcessStatus =
  | "RUNNING"
  | "COMPLETED"
  | "FAILED"
  | "SUSPENDED"
  | "STALLED"
  | "CANCELLED"
  | "SCHEDULED"
  | "WAITING_FOR_CHILD";

type AuditEventType =
  | "PROCESS_STARTED"
  | "STEP_TRANSITION"
  | "PROCESS_COMPLETED"
  | "PROCESS_FAILED"
  | "PROCESS_SUSPENDED"
  | "PROCESS_RESUMED"
  | "PROCESS_SCHEDULED"
  | "PROCESS_TIMER_FIRED"
  | "PROCESS_CANCELLED"
  | "PROCESS_STALLED"
  | "PROCESS_RETRIED"
  | "PROCESS_WOKEN"
  | "COMPENSATION_STEP"
  | "FORK_DISPATCHED"
  | "FORK_BRANCH_COMPLETED"
  | "FORK_JOINED"
  | "MULTI_INSTANCE_DISPATCHED"
  | "MULTI_INSTANCE_BRANCH_COMPLETED"
  | "MULTI_INSTANCE_JOINED"
  | "CHILD_PROCESS_STARTED"
  | "PARENT_RESUMED"
  | "PARENT_FAILED"
  | "HTTP_STEP_DISPATCHED"
  | "COMMAND_DISPATCHED"
  | "EVENT_RECEIVED"
  | "SIGNAL_RECEIVED"
  | "PROCESS_REPLAYED"
  | "WEBHOOK_DISPATCHED"
  | "WEBHOOK_FAILED";

// --- Request DTOs ---

interface StartFlowRequest {
  definitionName: string;
  definitionVersion?: number | null; // omit or null → use latest version
  initialData: Record<string, unknown>;
}

interface ProcessDefinitionRequest {
  name: string;
  initialStep: string;
  transitions: Record<string, TransitionRule[]>;
  compensations?: Record<string, string>; // step → compensation step
}

interface SignalRequest {
  event: string; // must match a key in the definition's transitions map
  data: Record<string, unknown>;
}

interface WebhookSubscriptionRequest {
  targetUrl: string;
  events: ("COMPLETED" | "FAILED" | "CANCELLED")[];
  processDefinitionName?: string | null; // null → matches all definitions
  secret?: string | null; // HMAC-SHA256 signing key
}

// --- Response DTOs ---

interface ProcessDefinitionResponse {
  id: number;
  name: string;
  version: number;
  initialStep: string;
  transitions: Record<string, TransitionRule[]>;
  compensations: Record<string, string>;
  createdAt: string; // ISO LocalDateTime, e.g. "2026-03-11T09:00:00"
  updatedAt: string;
}

interface ProcessInstanceResponse {
  id: string; // UUID
  definitionName: string;
  definitionVersion: number;
  currentStep: string;
  status: ProcessStatus;
  createdAt: string;
  stepStartedAt: string;
  completedAt: string | null;
  contextData: string; // JSON as string — must JSON.parse() to display
  parentProcessId: string | null; // UUID or null
}

interface MetricsSummaryResponse {
  total: number;
  running: number;
  completed: number;
  failed: number;
  stalled: number;
  cancelled: number;
  scheduled: number;
  waitingForChild: number;
  successRate: number; // 0.0 – 100.0
}

interface AuditLogResponse {
  id: string; // UUID
  processId: string; // UUID
  eventType: AuditEventType;
  stepName: string;
  fromStatus: string | null;
  toStatus: string | null;
  payload: string; // JSON string — contains contextSnapshot for key events
  occurredAt: string; // ISO LocalDateTime
}

interface WebhookSubscriptionResponse {
  id: string; // UUID
  processDefinitionName: string | null;
  targetUrl: string;
  events: string[];
  active: boolean;
  createdAt: string;
}

// --- TransitionRule (the heart of flow definitions) ---

interface HttpRequestConfig {
  url: string;    // SpEL supported, e.g. "'https://api.com/users/' + #userId"
  method: string; // "GET" | "POST" | "PUT" | "PATCH" | "DELETE"
  headers?: Record<string, string>; // header values support SpEL
  body?: string;  // request body string, supports SpEL
}

interface TransitionRule {
  condition?: string | null;         // SpEL expression, e.g. "#creditScore > 700"
  next?: string | null;              // next step name, or "COMPLETED" for terminal
  parallel?: string[] | null;        // fork: list of parallel step names
  joinStep?: string | null;          // fork/MI: step to advance to after all branches complete
  suspend?: boolean | null;          // true → process SUSPENDED at `next`, awaits signal
  delayMs?: number | null;           // delay in ms before dispatching `next`
  callActivity?: string | null;      // spawn child process from this definition name
  multiInstanceVariable?: string | null; // context key holding a List for scatter-gather
  outputMapping?: Record<string, string> | null; // target key → JSONPath expression
  httpRequest?: HttpRequestConfig | null; // inline HTTP call config
}
```

### 7.2. TransitionRule Field Combinations

A `TransitionRule` is polymorphic. Only certain field combinations are valid:

| Rule Type | Required Fields | Optional Fields |
|---|---|---|
| **Simple step** | `next` | `condition`, `outputMapping` |
| **Suspend gate** | `next`, `suspend: true` | `condition` |
| **Delay/Timer** | `next`, `delayMs` | `condition` |
| **Parallel fork** | `parallel`, `joinStep` | `condition` |
| **Call activity** | `callActivity`, `next` | `condition` |
| **Multi-instance** | `multiInstanceVariable`, `next`, `joinStep` | `condition` |
| **HTTP step** | `httpRequest`, `next` | `condition`, `outputMapping` |

All fields not listed for a rule type will be `null`/absent. The `@JsonInclude(NON_NULL)` annotation means null fields are omitted from the JSON payload.

### 7.3. Spring `Page<T>` Response Shape

All paginated endpoints (`GET /api/processes`, `GET /api/webhooks`) return Spring's standard Page wrapper:

```typescript
interface Page<T> {
  content: T[];
  pageable: {
    pageNumber: number;
    pageSize: number;
    offset: number;
    paged: boolean;
    unpaged: boolean;
  };
  totalElements: number;
  totalPages: number;
  last: boolean;
  first: boolean;
  number: number;      // current page number (same as pageable.pageNumber)
  size: number;        // page size
  numberOfElements: number; // items in this page
  empty: boolean;
  sort: {
    empty: boolean;
    unsorted: boolean;
    sorted: boolean;
  };
}
```

## 8. Error Handling & Response Format

The backend uses a `GlobalExceptionHandler` that maps exceptions to structured error responses:

| Exception | HTTP Status | Use Case |
|---|---|---|
| `NoSuchElementException` | **404** Not Found | Definition not found, process not found |
| `IllegalStateException` | **409** Conflict | Wrong status for operation (e.g., signal on non-SUSPENDED) |
| `IllegalArgumentException` | **400** Bad Request | Invalid payload, missing fields |

**Error response shape (consistent across all errors):**
```json
{
  "error": "Human-readable error message describing what went wrong"
}
```

The frontend should implement a global Axios interceptor that:
1. Catches 4xx responses and displays the `error` field as a toast notification.
2. Catches 5xx responses and shows a generic "Server error" toast.
3. Catches network errors and shows a "Backend unreachable" indicator.

## 9. Engine Concepts the Frontend Must Understand

### 9.1. Event Naming Convention (`_FINISHED` suffix)

The engine's event-driven model follows a strict naming convention:
- When a step named `STEP_X` completes, the worker publishes an event named `STEP_X_FINISHED`.
- The `transitions` map keys are these **event names** (e.g., `STEP_1_FINISHED`, `VALIDATE_CREDIT_FINISHED`).
- **Exception — Signal events:** When a step has `suspend: true`, the process waits for a signal. The signal's `event` name is a **custom key** that must also exist in the `transitions` map. For example, in `LOAN_APPROVAL`, when the process suspends at `MANUAL_REVIEW`, the signal event is `APPROVAL_GRANTED` (not `MANUAL_REVIEW_FINISHED`). The transitions map contains `"APPROVAL_GRANTED": [...]` as the key.

**Implication for the visualizer:** The parser must understand that:
- Keys ending in `_FINISHED` map from the step name (strip `_FINISHED` to get the source step).
- Keys NOT ending in `_FINISHED` are signal/custom events — they map from the step that was suspended when this signal is expected.

### 9.2. Sentinel / Pseudo-Steps

The `currentStep` field on a process instance can contain special sentinel values that are NOT real definition steps. The frontend must handle these gracefully:

| Sentinel Step | Meaning | When It Appears |
|---|---|---|
| `PARALLEL_WAIT` | Process is waiting for all parallel fork branches to complete | `status = RUNNING` with active parallel branches |
| `MULTI_INSTANCE_WAIT` | Process is waiting for all scatter-gather instances to complete | `status = RUNNING` with active MI branches |
| `COMPLETED` | Terminal pseudo-step; the process has finished | `status = COMPLETED` |

**Implication for the visualizer:** Do NOT render these as nodes. When `currentStep` is `PARALLEL_WAIT` or `MULTI_INSTANCE_WAIT`, highlight all the parallel/MI branch nodes as "in progress" instead.

### 9.3. Multi-Instance Step Suffixes (`__MI__`)

During scatter-gather execution, the engine creates indexed step names: `PROCESS_ORDER__MI__0`, `PROCESS_ORDER__MI__1`, etc. The base step name is `PROCESS_ORDER`.

**Implication for the visualizer:** Strip the `__MI__\d+` suffix when matching `currentStep` to definition nodes. The regex: `/^(.+)__MI__\d+$/`.

### 9.4. `contextData` Encoding

- In `ProcessInstanceResponse` (from `/api/processes`): `contextData` is a **JSON string** — you must `JSON.parse()` it.
- In `GET /status/{id}` response: `context` is a **parsed JSON object** — ready to use directly.
- When starting a flow (`POST /start-flow`), `initialData` is a **JSON object** in the request body.
- When signalling (`POST /signal`), `data` is a **JSON object** in the request body.

### 9.5. Process Versioning Behavior

- `POST /api/definitions` creates version 1.
- `PUT /api/definitions/{name}` creates version N+1 (does NOT mutate existing versions).
- `GET /api/definitions` returns only the **latest version** of each definition.
- `GET /api/definitions/{name}` returns only the **latest version**.
- Running process instances keep their `definitionVersion` snapshot — they are unaffected by later definition updates.
- When starting a flow, omitting `definitionVersion` uses the latest; specifying it pins to that exact version.

### 9.6. Which Admin Actions Are Valid per Status

| Action | Valid Statuses | Notes |
|---|---|---|
| **Cancel** | `RUNNING`, `STALLED`, `SUSPENDED`, `SCHEDULED`, `WAITING_FOR_CHILD` | Cascades cancel to active child processes |
| **Retry** | `FAILED`, `STALLED` | Resets to initial step |
| **Advance** | `RUNNING`, `STALLED` | Manually forces the next transition |
| **Wake** | `SCHEDULED` only | Bypasses remaining timer delay |
| **Signal** | `SUSPENDED` only | Injects event + data, evaluates transitions |
| **Replay** | Any non-`COMPLETED` | Rewinds to a historical step from audit trail |

**Implication for the UI:** Conditionally enable/disable action buttons based on the process `status`.

## 10. Complete Mock Data Reference

This section provides the exact JSON definitions for all 10 seeded flows plus mock data for process instances, audit trails, and webhooks. Use these to populate the MSW/mock layer.

### 10.1. All 10 Seeded Process Definitions

#### DEFAULT_FLOW — Simple linear two-step sequence
```json
{
  "name": "DEFAULT_FLOW",
  "version": 1,
  "initialStep": "STEP_1",
  "transitions": {
    "STEP_1_FINISHED": [{ "next": "STEP_2" }],
    "STEP_2_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

#### LOAN_APPROVAL — Conditional branching + human-in-the-loop suspend + output mapping
```json
{
  "name": "LOAN_APPROVAL",
  "version": 1,
  "initialStep": "VALIDATE_CREDIT",
  "transitions": {
    "VALIDATE_CREDIT_FINISHED": [
      {
        "condition": "#creditScore > 700",
        "next": "AUTO_APPROVE",
        "outputMapping": {
          "creditScore": "$.creditScore",
          "creditBureau": "$.meta.bureau"
        }
      },
      { "next": "MANUAL_REVIEW", "suspend": true }
    ],
    "AUTO_APPROVE_FINISHED": [{ "next": "DISBURSE_FUNDS" }],
    "APPROVAL_GRANTED": [
      { "condition": "#approved == true", "next": "DISBURSE_FUNDS" },
      { "next": "SEND_REJECTION" }
    ],
    "DISBURSE_FUNDS_FINISHED": [{ "next": "COMPLETED" }],
    "SEND_REJECTION_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```
*Note: The signal event key is `APPROVAL_GRANTED` (not `MANUAL_REVIEW_FINISHED`). When suspended at `MANUAL_REVIEW`, use `POST /signal` with `{"event": "APPROVAL_GRANTED", "data": {"approved": true}}`.*

#### ORDER_FULFILLMENT — Multi-branch conditional routing
```json
{
  "name": "ORDER_FULFILLMENT",
  "version": 1,
  "initialStep": "VALIDATE_ORDER",
  "transitions": {
    "VALIDATE_ORDER_FINISHED": [{ "next": "RESERVE_INVENTORY" }],
    "RESERVE_INVENTORY_FINISHED": [
      { "condition": "#inventoryAvailable == true", "next": "PROCESS_PAYMENT" },
      { "next": "NOTIFY_OUT_OF_STOCK" }
    ],
    "PROCESS_PAYMENT_FINISHED": [
      { "condition": "#paymentSuccess == true", "next": "SHIP_ORDER" },
      { "next": "NOTIFY_PAYMENT_FAILED" }
    ],
    "SHIP_ORDER_FINISHED": [{ "next": "COMPLETED" }],
    "NOTIFY_OUT_OF_STOCK_FINISHED": [{ "next": "COMPLETED" }],
    "NOTIFY_PAYMENT_FAILED_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

#### PARALLEL_FLOW — Fork/join (AND gateway)
```json
{
  "name": "PARALLEL_FLOW",
  "version": 1,
  "initialStep": "PREPARE_APPLICATION",
  "transitions": {
    "PREPARE_APPLICATION_FINISHED": [
      { "parallel": ["VALIDATE_CREDIT", "VERIFY_IDENTITY"], "joinStep": "APPROVE_LOAN" }
    ],
    "APPROVE_LOAN_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

#### PAYMENT_SAGA — Saga pattern with compensation/rollback
```json
{
  "name": "PAYMENT_SAGA",
  "version": 1,
  "initialStep": "RESERVE_INVENTORY",
  "transitions": {
    "RESERVE_INVENTORY_FINISHED": [{ "next": "CHARGE_PAYMENT" }],
    "CHARGE_PAYMENT_FINISHED": [{ "next": "SHIP_ORDER" }],
    "SHIP_ORDER_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {
    "RESERVE_INVENTORY": "UNDO_RESERVE_INVENTORY",
    "CHARGE_PAYMENT": "REFUND_PAYMENT"
  }
}
```

#### DELAY_FLOW — Timer/delay step
```json
{
  "name": "DELAY_FLOW",
  "version": 1,
  "initialStep": "PREPARE_REQUEST",
  "transitions": {
    "PREPARE_REQUEST_FINISHED": [{ "delayMs": 3000, "next": "PROCESS_REQUEST" }],
    "PROCESS_REQUEST_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

#### CREDIT_CHECK_SUB — Child definition used by SUB_PROCESS_FLOW
```json
{
  "name": "CREDIT_CHECK_SUB",
  "version": 1,
  "initialStep": "FETCH_CREDIT_REPORT",
  "transitions": {
    "FETCH_CREDIT_REPORT_FINISHED": [{ "next": "EVALUATE_SCORE" }],
    "EVALUATE_SCORE_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

#### SUB_PROCESS_FLOW — Call activity (sub-process invocation)
```json
{
  "name": "SUB_PROCESS_FLOW",
  "version": 1,
  "initialStep": "COLLECT_APPLICATION",
  "transitions": {
    "COLLECT_APPLICATION_FINISHED": [
      { "callActivity": "CREDIT_CHECK_SUB", "next": "MAKE_DECISION" }
    ],
    "MAKE_DECISION_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

#### SCATTER_GATHER_FLOW — Multi-instance scatter-gather
```json
{
  "name": "SCATTER_GATHER_FLOW",
  "version": 1,
  "initialStep": "RECEIVE_ORDERS",
  "transitions": {
    "RECEIVE_ORDERS_FINISHED": [
      { "multiInstanceVariable": "orderItems", "next": "PROCESS_ORDER", "joinStep": "SHIP_ORDERS" }
    ],
    "SHIP_ORDERS_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```
*Start with: `{"initialData": {"orderItems": [{"sku": "WIDGET-A", "qty": 2}, {"sku": "WIDGET-B", "qty": 1}]}}`*

#### HTTP_STEP_FLOW — Native HTTP REST step (no Kafka dispatch)
```json
{
  "name": "HTTP_STEP_FLOW",
  "version": 1,
  "initialStep": "STEP_1",
  "transitions": {
    "STEP_1_FINISHED": [
      {
        "httpRequest": {
          "url": "https://jsonplaceholder.typicode.com/todos/1",
          "method": "GET",
          "headers": { "Accept": "application/json" }
        },
        "next": "STEP_2"
      }
    ],
    "STEP_2_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```

### 10.2. Mock Process Instances (Various States)

Use these to populate the mock process listing and detail views. Each demonstrates a different status:

```typescript
const mockProcessInstances: ProcessInstanceResponse[] = [
  {
    id: "a1b2c3d4-0001-0001-0001-000000000001",
    definitionName: "DEFAULT_FLOW",
    definitionVersion: 1,
    currentStep: "STEP_2",
    status: "RUNNING",
    createdAt: "2026-03-11T09:00:00",
    stepStartedAt: "2026-03-11T09:00:05",
    completedAt: null,
    contextData: "{\"userId\": 42}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000002",
    definitionName: "LOAN_APPROVAL",
    definitionVersion: 1,
    currentStep: "COMPLETED",
    status: "COMPLETED",
    createdAt: "2026-03-11T08:30:00",
    stepStartedAt: "2026-03-11T08:30:20",
    completedAt: "2026-03-11T08:30:25",
    contextData: "{\"creditScore\": 750, \"approved\": true}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000003",
    definitionName: "ORDER_FULFILLMENT",
    definitionVersion: 1,
    currentStep: "PROCESS_PAYMENT",
    status: "FAILED",
    createdAt: "2026-03-11T08:00:00",
    stepStartedAt: "2026-03-11T08:00:15",
    completedAt: "2026-03-11T08:00:20",
    contextData: "{\"inventoryAvailable\": true, \"paymentSuccess\": false, \"error\": \"Payment gateway timeout\"}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000004",
    definitionName: "LOAN_APPROVAL",
    definitionVersion: 1,
    currentStep: "MANUAL_REVIEW",
    status: "SUSPENDED",
    createdAt: "2026-03-11T09:10:00",
    stepStartedAt: "2026-03-11T09:10:03",
    completedAt: null,
    contextData: "{\"creditScore\": 500}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000005",
    definitionName: "DEFAULT_FLOW",
    definitionVersion: 1,
    currentStep: "STEP_1",
    status: "STALLED",
    createdAt: "2026-03-11T07:00:00",
    stepStartedAt: "2026-03-11T07:00:00",
    completedAt: null,
    contextData: "{\"userId\": 99}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000006",
    definitionName: "DELAY_FLOW",
    definitionVersion: 1,
    currentStep: "PROCESS_REQUEST",
    status: "SCHEDULED",
    createdAt: "2026-03-11T09:15:00",
    stepStartedAt: "2026-03-11T09:15:02",
    completedAt: null,
    contextData: "{}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000007",
    definitionName: "SUB_PROCESS_FLOW",
    definitionVersion: 1,
    currentStep: "COLLECT_APPLICATION",
    status: "WAITING_FOR_CHILD",
    createdAt: "2026-03-11T09:20:00",
    stepStartedAt: "2026-03-11T09:20:05",
    completedAt: null,
    contextData: "{\"applicantId\": \"A-001\"}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000008",
    definitionName: "CREDIT_CHECK_SUB",
    definitionVersion: 1,
    currentStep: "FETCH_CREDIT_REPORT",
    status: "RUNNING",
    createdAt: "2026-03-11T09:20:06",
    stepStartedAt: "2026-03-11T09:20:06",
    completedAt: null,
    contextData: "{\"applicantId\": \"A-001\"}",
    parentProcessId: "a1b2c3d4-0001-0001-0001-000000000007"
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000009",
    definitionName: "PARALLEL_FLOW",
    definitionVersion: 1,
    currentStep: "PARALLEL_WAIT",
    status: "RUNNING",
    createdAt: "2026-03-11T09:25:00",
    stepStartedAt: "2026-03-11T09:25:02",
    completedAt: null,
    contextData: "{}",
    parentProcessId: null
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000010",
    definitionName: "PAYMENT_SAGA",
    definitionVersion: 1,
    currentStep: "UNDO_RESERVE_INVENTORY",
    status: "RUNNING",
    createdAt: "2026-03-11T09:30:00",
    stepStartedAt: "2026-03-11T09:30:15",
    completedAt: null,
    contextData: "{\"error\": \"Payment declined\"}",
    parentProcessId: null
  }
];
```

### 10.3. Mock Audit Trail

Example audit trail for a completed `LOAN_APPROVAL` process (high credit score path):

```typescript
const mockAuditTrail: AuditLogResponse[] = [
  {
    id: "audit-0001",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "PROCESS_STARTED",
    stepName: "VALIDATE_CREDIT",
    fromStatus: null,
    toStatus: "RUNNING",
    payload: "{\"contextSnapshot\":{\"creditScore\":750}}",
    occurredAt: "2026-03-11T08:30:00"
  },
  {
    id: "audit-0002",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "COMMAND_DISPATCHED",
    stepName: "VALIDATE_CREDIT",
    fromStatus: null,
    toStatus: null,
    payload: "{\"commandType\":\"VALIDATE_CREDIT\"}",
    occurredAt: "2026-03-11T08:30:01"
  },
  {
    id: "audit-0003",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "EVENT_RECEIVED",
    stepName: "VALIDATE_CREDIT",
    fromStatus: null,
    toStatus: null,
    payload: "{\"eventType\":\"VALIDATE_CREDIT_FINISHED\"}",
    occurredAt: "2026-03-11T08:30:10"
  },
  {
    id: "audit-0004",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "STEP_TRANSITION",
    stepName: "AUTO_APPROVE",
    fromStatus: "RUNNING",
    toStatus: "RUNNING",
    payload: "{\"contextSnapshot\":{\"creditScore\":750,\"creditBureau\":\"Experian\"}}",
    occurredAt: "2026-03-11T08:30:10"
  },
  {
    id: "audit-0005",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "COMMAND_DISPATCHED",
    stepName: "AUTO_APPROVE",
    fromStatus: null,
    toStatus: null,
    payload: "{\"commandType\":\"AUTO_APPROVE\"}",
    occurredAt: "2026-03-11T08:30:10"
  },
  {
    id: "audit-0006",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "EVENT_RECEIVED",
    stepName: "AUTO_APPROVE",
    fromStatus: null,
    toStatus: null,
    payload: "{\"eventType\":\"AUTO_APPROVE_FINISHED\"}",
    occurredAt: "2026-03-11T08:30:15"
  },
  {
    id: "audit-0007",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "STEP_TRANSITION",
    stepName: "DISBURSE_FUNDS",
    fromStatus: "RUNNING",
    toStatus: "RUNNING",
    payload: "{\"contextSnapshot\":{\"creditScore\":750,\"creditBureau\":\"Experian\",\"approved\":true}}",
    occurredAt: "2026-03-11T08:30:15"
  },
  {
    id: "audit-0008",
    processId: "a1b2c3d4-0001-0001-0001-000000000002",
    eventType: "PROCESS_COMPLETED",
    stepName: "COMPLETED",
    fromStatus: "RUNNING",
    toStatus: "COMPLETED",
    payload: null,
    occurredAt: "2026-03-11T08:30:25"
  }
];
```

### 10.4. Mock Metrics Summary

```typescript
const mockMetrics: MetricsSummaryResponse = {
  total: 150,
  running: 12,
  completed: 118,
  failed: 5,
  stalled: 2,
  cancelled: 8,
  scheduled: 3,
  waitingForChild: 2,
  successRate: 78.67
};
```

### 10.5. Mock Webhook Subscriptions

```typescript
const mockWebhooks: WebhookSubscriptionResponse[] = [
  {
    id: "wh-0001-0001-0001-000000000001",
    processDefinitionName: "LOAN_APPROVAL",
    targetUrl: "https://api.example.com/hooks/loan-events",
    events: ["COMPLETED", "FAILED"],
    active: true,
    createdAt: "2026-03-10T12:00:00"
  },
  {
    id: "wh-0001-0001-0001-000000000002",
    processDefinitionName: null,
    targetUrl: "https://monitoring.example.com/all-events",
    events: ["COMPLETED", "FAILED", "CANCELLED"],
    active: true,
    createdAt: "2026-03-09T08:00:00"
  },
  {
    id: "wh-0001-0001-0001-000000000003",
    processDefinitionName: "ORDER_FULFILLMENT",
    targetUrl: "https://old-system.example.com/notify",
    events: ["FAILED"],
    active: false,
    createdAt: "2026-03-08T15:30:00"
  }
];
```

## 11. Transitions-to-Graph Parser Guide

This section provides a detailed algorithm for the `parseTransitionsToGraph()` function that converts a process definition's `transitions` map into React Flow `Node[]` and `Edge[]`.

### 11.1. Parser Algorithm

```
Input:  initialStep: string,
        transitions: Record<string, TransitionRule[]>,
        compensations: Record<string, string>

Output: { nodes: Node[], edges: Edge[] }
```

**Step 1 — Collect all step names:**
- Start with `initialStep`.
- Walk all transition rules and collect every `next`, every item in `parallel[]`, every `joinStep`, and every `callActivity` target.
- Also collect all compensation step names from the `compensations` map values.
- Add `COMPLETED` as a terminal node.

**Step 2 — Classify each step's node type** by examining which transition rule leads TO it:
- If a rule with `suspend: true` targets this step → **Human Task** node
- If a rule with `delayMs` targets this step → **Timer** node
- If a rule with `callActivity` targets this step → **Sub-Process** node (the step AFTER the call)
- If a rule with `httpRequest` targets this step → **HTTP Task** node (the step AFTER the HTTP call)
- If the step appears in `compensations` values → **Compensation** node
- Otherwise → **Default Task** node

**Important nuance:** The `httpRequest` and `callActivity` rules don't create separate nodes — the HTTP call or child process invocation happens *between* the triggering event and the `next` step. However, for visualization clarity, you should insert a **virtual gateway node** representing the HTTP call or sub-process invocation between the source step and the `next` step.

**Step 3 — Detect gateways** by examining each transition rule array:
- If the array has >1 rules (multiple branches) → Insert a **Diamond/XOR gateway** node after the source step.
- If a rule has `parallel` → Insert a **Fork gateway** node (outbound) and a **Join gateway** node (inbound to `joinStep`).
- If a rule has `multiInstanceVariable` → Insert a **Multi-Instance gateway** node.

**Step 4 — Create edges:**
- For each simple `{next}` rule: edge from source step → next step.
- For conditional branches: edge from source step → diamond gateway → one edge per rule to its target. Label each edge with the `condition` text (or "default" if null).
- For parallel forks: edge from source → fork gateway → one edge per parallel step. Then edge from each parallel step → join gateway → `joinStep`.
- For multi-instance: edge from source → MI gateway → `next` step (with MI icon/label). Then edge from `next` → `joinStep`.
- For compensations: dashed edges from each step to its compensation step.

### 11.2. Recommended Node Styles

| Node Type | Shape | Color | Icon |
|---|---|---|---|
| Default Task | Rounded rectangle | Slate/gray | `Play` (Lucide) |
| HTTP Task | Rounded rectangle | Blue/cyan | `Globe` |
| Timer/Delay | Rounded rectangle | Purple | `Clock` |
| Human Task (Suspend) | Rounded rectangle | Orange/amber | `UserCheck` or `Hand` |
| Sub-Process Task | Rounded rectangle, double border | Teal | `Layers` |
| Multi-Instance | Rounded rectangle, triple lines at bottom | Indigo | `Copy` or three stacked bars |
| Compensation | Rounded rectangle, dashed border | Red/rose | `Undo2` |
| XOR Gateway (Diamond) | Diamond | White/transparent | None (just diamond) |
| Fork Gateway | Diamond with `+` | White/transparent | `+` symbol |
| Join Gateway | Diamond with `+` | White/transparent | `+` symbol |
| COMPLETED | Circle/pill | Green | `CheckCircle` |

### 11.3. Live Execution Highlighting

When viewing a running process instance in the detail view:

| State | Highlight |
|---|---|
| Current step (`currentStep` matches) | Glowing pulse border (indigo/blue), animated |
| Completed steps (from audit trail) | Green border / green fill |
| Failed step | Red border / red fill |
| `PARALLEL_WAIT` / `MULTI_INSTANCE_WAIT` | Highlight ALL parallel/MI branch nodes as "in progress" |
| Compensation steps (if `compensating = true`) | Red dashed pulse on active compensation node |

To determine which steps are completed, use the audit trail: filter for `STEP_TRANSITION` events and collect all `stepName` values — those are the steps that were successfully entered.

## 12. Detailed Task Breakdown (Iterative To-Do List)

The execution will follow atomic, actionable tasks to maintain high velocity and manageable PRs.

### 12.1. Sprint 1: Project Initialization & Infrastructure
- [ ] **1.1:** Initialize the project using `npm create vite@latest edoe-frontend -- --template react-ts`.
- [ ] **1.2:** Install and configure `tailwindcss`, `postcss`, and `autoprefixer`. Initialize `tailwind.config.js`.
- [ ] **1.3:** Setup absolute path aliases (e.g., `@/` maps to `./src/`) in `vite.config.ts` and `tsconfig.json`.
- [ ] **1.4:** Install essential core libraries: `react-router-dom`, `@tanstack/react-query`, `lucide-react`, `axios`, `clsx`, `tailwind-merge`.
- [ ] **1.5:** Run `npx shadcn-ui@latest init` to establish base design tokens and theme variables (CSS variables for dark/light modes).
- [ ] **1.6:** Setup an Axios instance/interceptor with a configurable base URL (`import.meta.env.VITE_API_URL`) and global error handling.

### 12.2. Sprint 2: Core Layout & Routing Shell
- [ ] **2.1:** Develop the `Sidebar` component mapping out primary navigation links (`/`, `/definitions`, `/instances`, `/webhooks`).
- [ ] **2.2:** Develop the `TopBar` component featuring breadcrumb navigation and a Dark/Light Theme Toggle.
- [ ] **2.3:** Create the `AppShell` layout wrapper that coordinates the Sidebar, TopBar, and `<Outlet />`.
- [ ] **2.4:** Configure React Router in `App.tsx` defining standard layout routes and lazy-loaded page components for performance.
- [ ] **2.5:** Add `shadcn/ui` foundational components: Button, Input, Card, Table, Dialog, Sheet, and Toaster (Sonner).

### 12.3. Sprint 3: API Integration, Mocking & Dashboard
- [ ] **3.1:** Create `types/api.ts` mapping all backend DTOs (e.g., `ProcessInstanceResponse`, `MetricsSummaryResponse`, `TransitionRule`).
- [ ] **3.2:** Implement an API Adapter or MSW (Mock Service Worker) configuration (`VITE_USE_MOCK_DATA=true`).
- [ ] **3.3:** Populate the mock definition store with the 10 example flows from the backend's `README.md` (e.g., `DEFAULT_FLOW`, `LOAN_APPROVAL`, `ORDER_FULFILLMENT`, `PARALLEL_FLOW`, `PAYMENT_SAGA`, `DELAY_FLOW`, `CREDIT_CHECK_SUB`, `SUB_PROCESS_FLOW`, `SCATTER_GATHER_FLOW`, `HTTP_STEP_FLOW`).
- [ ] **3.4:** Create mock responses for metrics (`/api/metrics/summary`) and recent process instances to support dashboard development without the backend.
- [ ] **3.5:** Write custom React Query hooks for fetching metrics (`useMetricsSummary`) that pass through the API Adapter.
- [ ] **3.6:** Implement Dashboard metrics cards mapping data to dynamic Lucide icons and colors based on health/status.
- [ ] **3.7:** Write custom hooks for fetching recent process instances.
- [ ] **3.8:** Build the Dashboard Activity Table to display recent runs.

### 12.4. Sprint 4: Process Definitions & Visualizer Setup
- [ ] **4.1:** Write custom hooks (`useDefinitions`, `useCreateDefinition`, `useDeleteDefinition`).
- [ ] **4.2:** Build the Definitions page with a data table and "Create New" context menu.
- [ ] **4.3:** Install `@monaco-editor/react`. Build a reusable `JsonEditor` component for editing definition rules.
- [ ] **4.4:** Build the "Start Flow" modal dialog (Definition selector, Version input, Monaco editor for `initialData`).
- [ ] **4.5:** Install `reactflow`. Write an adapter parser function to transform EDOE `transitions_json` rules (conditonal, parallel, sub-process) into React Flow `Node[]` and `Edge[]`.
- [ ] **4.6:** Develop the `DefinitionVisualizer` component rendering the parsed node graph.

### 12.5. Sprint 5: Process Monitoring & Detail View
- [ ] **5.1:** Write hooks (`useProcessInstances` with pagination and filter arguments).
- [ ] **5.2:** Build the Instances table supporting backend pagination, definition dropdown filtering, and status filtering.
- [ ] **5.3:** Build the Process Detail outer shell (`/instances/:id`) incorporating the Header Ribbon, dynamic Status Badges, and Parent/Child Navigation links. Add step duration calculations using `stepStartedAt`.
- [ ] **5.4:** Implement automatic data refetching/polling (e.g., `refetchInterval: 3000`) for instances still in `RUNNING`, `SCHEDULED`, or `WAITING_FOR_CHILD`.
- [ ] **5.5:** Embed the `DefinitionVisualizer` inside the Process Detail page. Enhance the adapter parser to apply specific styling (CSS classes/Tailwind) to the `current_step` node and failed nodes (including stripping `__MI__\d+` step suffixes for Multi-Instance support).

### 12.6. Sprint 6: Audit Trail & Complex Interventions
- [ ] **6.1:** Write the `useAuditTrail` query hook.
- [ ] **6.2:** Develop an `AuditTimeline` custom component leveraging Tailwind CSS borders/circles to visualize state changes and `contextSnapshot` payloads beautifully.
- [ ] **6.3:** Build reusable JSON context viewer tab for real-time process state inspection.
- [ ] **6.4:** Implement intervention mutation hooks: `useCancelProcess`, `useRetryProcess`, `useAdvanceProcess`, `useWakeProcess`.
- [ ] **6.5:** Hook administrative mutation buttons in the Header Ribbon to their respective functions, including confirmation alerts.
- [ ] **6.6:** Build and integrate the "Signal Modal" form (Event Type input, Data JSON input) calling `/api/processes/{id}/signal`.
- [ ] **6.7:** Build the "Replay / Time-Travel Modal". Pull the audit trail, allow the user to select an eligible `PROCESS_STARTED` or `STEP_TRANSITION` event, and submit to `/api/processes/{id}/replay?fromStep=X`.

### 12.7. Sprint 7: Webhooks & Final Polish
- [ ] **7.1:** Write Webhook CRUD and Toggle query/mutation hooks.
- [ ] **7.2:** Build the Webhooks management table displaying active states and subscribed events.
- [ ] **7.3:** Build the Webhook creation Slide-out Panel (URL input, Event Multi-select using a custom combobox or checkboxes, Secret input).
- [ ] **7.4:** Implement Empty States and Loading Skeletons (`shadcn/ui` Skeleton) across all tables and dashboard widgets.
- [ ] **7.5:** Audit responsive layouts to ensure the frontend works effectively on smaller laptop screens or tablets.
- [ ] **7.6:** Final QA, console warning cleanups, and deploy configuration settings (e.g., multi-environment `env` support).
