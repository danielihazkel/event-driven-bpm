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
  - `status` — One of: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED`, `SCHEDULED`, `WAITING_FOR_CHILD`, `COMPENSATION_FAILED`
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
- **Acknowledge Compensation Failure:** `POST /acknowledge-compensation-failure` - (No body) Transitions `COMPENSATION_FAILED → CANCELLED` after manual DB remediation. Returns `ProcessInstanceResponse`.

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
    "compensationFailed": 0,
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

### 3.6. Human Tasks (`/api/tasks`)

Human tasks are first-class entities created automatically whenever a process matches a `humanTask` transition rule.

**List Tasks**
- **Endpoint:** `GET /api/tasks?status=PENDING&assignee=john`
- **Query Parameters (all optional):**
  - `status` — One of: `PENDING`, `COMPLETED`, `CANCELLED`
  - `assignee` — Filter by assignee string (as stored, after SpEL resolution)
- **Response `HumanTaskResponse[]`:**
  ```json
  [
    {
      "id": "550e8400-...",
      "processInstanceId": "123e4567-...",
      "processDefinitionName": "LOAN_APPROVAL",
      "taskName": "Manual Loan Review",
      "signalEvent": "APPROVAL_GRANTED",
      "formSchema": {
        "fields": [
          { "name": "approved", "type": "boolean", "label": "Approve?" },
          { "name": "notes",    "type": "string",  "label": "Notes" }
        ]
      },
      "assignee": null,
      "status": "PENDING",
      "createdAt": "2026-03-11T09:10:03",
      "completedAt": null,
      "resultData": null
    }
  ]
  ```

**Get Single Task**
- **Endpoint:** `GET /api/tasks/{id}`
- **Response:** Single `HumanTaskResponse`. Returns 404 if not found.

**Complete a Task**
- **Endpoint:** `POST /api/tasks/{id}/complete`
- **Payload `CompleteTaskRequest`:**
  ```json
  { "resultData": { "approved": true, "notes": "Credit history looks good" } }
  ```
- **Response:** Updated `HumanTaskResponse` with `status: "COMPLETED"`.
- **Side-effect:** The engine automatically signals the associated process using `signalEvent` and `resultData` as signal data. The process resumes through its normal transition rules.
- **Error:** Returns 409 if the task is not `PENDING`.

**Cancel a Task**
- **Endpoint:** `POST /api/tasks/{id}/cancel`
- **Response:** Updated `HumanTaskResponse` with `status: "CANCELLED"`.
- **Note:** Does NOT signal the process. Use this when the task is no longer needed (e.g., the process was cancelled externally). The task-inbox UI should call this only when explicitly discarding a task.

**Important relationship: `signalEvent` ↔ transitions map**

When `POST /api/tasks/{id}/complete` is called:
1. The task is marked `COMPLETED` with the submitted `resultData`.
2. The engine calls `POST /api/processes/{processInstanceId}/signal` internally with `event = task.signalEvent` and `data = resultData`.
3. The process evaluates its `transitions["APPROVAL_GRANTED"]` rules (SpEL conditions see `resultData` fields as context variables).

This means `resultData` fields (e.g. `approved`, `notes`) become available as `#approved`, `#notes` in any SpEL conditions on the signal transition rules.

### 3.7. Authentication (OAuth2 / JWT)

All backend endpoints now require a valid JWT Bearer token. The frontend must:

1. **Store the token** — after obtaining a token (dev: `GET /dev/token?role=ROLE_ADMIN`; prod: from your IdP), persist it in `localStorage` under `edoe_token`.
2. **Attach to every request** — the Axios instance in `lib/api-client.ts` must add `Authorization: Bearer <token>` to every outgoing request.
3. **Handle 401** — on `401 Unauthorized`, clear the stored token and redirect to a login/token-entry page.
4. **Handle 403** — on `403 Forbidden`, show a toast: "Insufficient permissions".

**Role constraints relevant to the UI:**

| Role | What UI elements to show/hide |
|---|---|
| `ROLE_VIEWER` | Read-only views: Dashboard, Definitions list (no create/edit/delete), Instances list (no cancel/retry/advance), Audit trail, Metrics |
| `ROLE_ADMIN` | Full access: all CRUD, cancel/retry/advance/wake/signal/replay/acknowledge buttons |

**Dev token endpoint (only active with `--spring.profiles.active=dev`):**
```
GET /dev/token?subject=admin&role=ROLE_ADMIN
→ { "token": "<hs256-jwt>" }
```

In the dev mock layer (`VITE_USE_MOCK_DATA=true`) authentication can be skipped entirely — all API adapter calls bypass the real backend.

## 4. UI/UX Design System & Layout Structure
- **Aesthetics:** Sleek, modern, dashboard-centric design. Support for both Dark Mode and Light Mode with seamless switching. Use of subtle glassmorphism for floating overlays, and vibrant but professional colors (e.g., slate/zinc backgrounds with indigo/violet primary accents to signify active states).
- **Interactions:** Subtle hover effects on interactive elements, smooth data transitions, skeleton loading states while React Query fetches data, and accessible toast notifications for API success/errors (e.g., Sonner).

### 4.1. Global Application Layout Wireframe
The app will use a standard Sidebar + Topbar layout to prioritize horizontal space for data tables and visual graphs.
```text
┌─────────────────────────────────────────────────────────────┐
│ ✦ EDOE Admin     🍞 Dashboard / Process Instances      [🌙] │
├───────────────┬─────────────────────────────────────────────┤
│ 📊 Dashboard  │                                             │
│ 📋 Definitions│                                             │
│ ⚡ Instances  │              MAIN CONTENT AREA              │
│ 👤 Tasks  (3) │             (Scrollable block)              │
│ 🪝 Webhooks   │                                             │
│ ⚙️ Settings   │                                             │
│               │                                             │
└───────────────┴─────────────────────────────────────────────┘
```
- **Left Sidebar:** Collapsible navigation menu housing links to the core views. The Tasks link shows a live badge count of `PENDING` human tasks.
- **Top App Bar:** Breadcrumbs for navigational context, Global Search, Theme Toggle, and Environment/Connection Health Indicator.
- **Main Content Area:** Scrollable container for page content.

## 5. Detailed Page Specifications & Mockups

### 5.1. Dashboard (`/`)
- **Purpose:** At-a-glance view of system health and quick navigational shortcuts.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ Dashboard                                [Start New Flow 🚀]│
├─────────────────────────────────────────────────────────────┤
│ System Health Status                                        │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ │
│ │ Total: 150 │ │ Running: 5 │ │ Failed: 2🔴│ │ Success:85%│ │
│ └────────────┘ └────────────┘ └────────────┘ └────────────┘ │
├─────────────────────────────────────────────────────────────┤
│ Recent Activity                                             │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ ID          | FLOW            | STATUS    | TIME        │ │
│ │ 550e84...   | LOAN_APPROVAL   | [RUNNING] | 2 mins ago  │ │
│ │ 123e45...   | DEFAULT_FLOW    | [FAILED]  | 5 mins ago  │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```
- **Key Components:**
  - **Metric Cards Grid:** Top row displaying total processes, running, completed, failed, stalled, and overall success rate (fetched from `/api/metrics/summary`).
  - **Quick Actions Bar:** "Start New Flow" and "Create Definition" prominent buttons.
  - **Recent Activity Table:** Top 5-10 most recently started or failed instances to alert the user of immediate issues.
- **Functions:** Cards dynamically colorize (e.g., Failed > 0 turns red). Clicking "Start New Flow" opens a modal.

### 5.2. Process Definitions (`/definitions`)
- **Purpose:** Manage, explore, and configure workflow schemas.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ Process Definitions                      [+ Create New]     │
├───────────────┬─────────────────────────────────────────────┤
│ 🔍 Search...  │ ID | Name              | Version | Actions  │
│               ├─────────────────────────────────────────────┤
│ [All]         │ 1  | LOAN_APPROVAL     | v1      | [Edit]   │
│ [Active]      │ 2  | PAYMENT_SAGA      | v1      | [Edit]   │
│ [Archived]    │ 3  | DEFAULT_FLOW      | v3      | [Edit]   │
└───────────────┴─────────────────────────────────────────────┘
```
- **Key Components:** Data Table with Name, Latest Version, Created At, and Actions (Edit, Delete, Start).
- **Functions:** Pressing "Edit" routes into the Definition Editor View.

### 5.3. Definition Editor View (`/definitions/:name`)
- **Purpose:** Visually inspect and manually edit the transition rules for a specific workflow version.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ 🍞 Definitions / LOAN_APPROVAL                [Save New v2] │
├───────────────────┬─────────────────────────────────────────┤
│ JSON MODE         │ VISUAL GRAPH MODE                       │
│ ┌───────────────┐ │ ┌─────────────────────────────────────┐ │
│ │"transitions":{│ │ │ ● START --> [VALIDATE_CREDIT] --> ◇ │ │
│ │ "STEP_1_FI... │ │ │                                     │ │
│ │               │ │ │                                     │ │
│ └───────────────┘ │ └─────────────────────────────────────┘ │
└───────────────────┴─────────────────────────────────────────┘
```
- **Key Components:** Header ribbon, Version selector (to view read-only history versions). Split pane:
  - **JSON Mode (Monaco):** Editor for raw transition limits and payload definitions.
  - **Visual Mode (React Flow):** Canvas rendering node boxes and edges synced instantly from the JSON.
- **Functions:** Submitting saves a completely new version (N+1) of the flow.

### 5.4. Process Instances / Monitoring (`/instances`)
- **Purpose:** Track real-time execution of processes across the system.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ Process Instances                                           │
├─────────────────────────────────────────────────────────────┤
│ Filters: [Definition: All ⌄] [Status: RUNNING ⌄] 🔍 Search..│
├─────────────────────────────────────────────────────────────┤
│ ID             | Definition      | Step       | Status      │
│ 550e8400-e2... | LOAN_APPROVAL   | STEP_2     | 🔵 RUNNING   │
│ 123e4567-e8... | PAYMENT_SAGA    | ROLLBACK   | 🔴 FAILED    │
│ < Prev | Page 1 of 5 | Next >                               │
└─────────────────────────────────────────────────────────────┘
```
- **Key Components:** Command Bar with filters, and a Server-Side Paginated React Table showing current step, execution times, and status markers.

### 5.5. Process Detail View (`/instances/:id`)
- **Purpose:** Deep dive into a single process execution, troubleshooting, and manual administrative intervention.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ 🍞 Instances / 550e84...               [Cancel] [Advance] ⚙️│
├──────────────────────────────┬──────────────────────────────┤
│ LIVE VISUAL TRACKER          │ DETAILS TAB: [Context] [Log] │
│ ┌──────────────────────────┐ │ ┌──────────────────────────┐ │
│ │ ● START                  │ │ │ { "creditScore": 750 }   │ │
│ │   ↓                      │ │ └──────────────────────────┘ │
│ │ [VALIDATE_CREDIT] (✅)   │ │ ┌──────────────────────────┐ │
│ │   ↓                      │ │ │ 09:00 - STEP STARTED     │ │
│ │ [DISBURSE_FUNDS]  (✨)   │ │ │ 09:01 - TRANSITION       │ │
│ └──────────────────────────┘ │ └──────────────────────────┘ │
└──────────────────────────────┴──────────────────────────────┘
```
- **Key Components:**
  - **Header Ribbon:** Action buttons (Cancel, Retry, Advance, Force Wake, Signal).
  - **Left Visual Tracker:** React Flow visualization parsed from the original definition. Active step gets a glowing ring (✨). Errored step pulses red, completed steps checkmark green (✅).
  - **Right Tabs:** Displays read-only payload variables (`contextData`) and a vertical audit-log timeline.
- **Functions:** "Signal" button opens a modal requesting an Event string & payload JSON. "Time-Travel / Replay" button allows selecting a dot on the audit trail to revert state.

### 5.6. Human Task Inbox (`/tasks`)
- **Purpose:** Centralized queue for human workers to complete interactive forms generated by process definitions.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ Human Tasks (Inbox)                                         │
├───────────────────────┬─────────────────────────────────────┤
│ 🔍 Filters [PENDING ⌄]│ Task: Manual Loan Review            │
│                       │ Flow: LOAN_APPROVAL (550e8400...)   │
│ ✧ Manual Loan Review  │-------------------------------------│
│   LOAN_APPROVAL       │ Approve?  [ ]                       │
│                       │ Notes:    [_______________________] │
│ ✧ Review Submission   │                                     │
│   HUMAN_TASK_FLOW     │                   [ Complete Task ] │
└───────────────────────┴─────────────────────────────────────┘
```
- **Key Components:** Two-column split interface. Left side offers task queue. Right side dynamically renders HTML inputs (checkboxes, inputs) strictly driven by the `formSchema` JSON attached to the step definition.
- **Functions:** Upon clicking "Complete Task", payload runs `POST /tasks/{id}/complete` effectively unblocking the paused flow.

### 5.7. Webhooks Management (`/webhooks`)
- **Purpose:** Manage subscriptions to terminal state events for system integrations.

#### Wireframe
```text
┌─────────────────────────────────────────────────────────────┐
│ Webhooks                                     [+ Add Webhook]│
├─────────────────────────────────────────────────────────────┤
│ URL                   | Events        | Target Flow | Active│
│ https://api.ex.com/ho | COMPLETED, FA | [All Flows] | [ON]  │
│ https://old.sys.com/  | FAILED        | ORDER_FULF  | [OFF] │
└─────────────────────────────────────────────────────────────┘
```
- **Key Components:** Data table with Quick-toggle switch (`/api/webhooks/{id}/toggle`). Slideout offcanvas modal to create new subscriptions defining target URLs and specific `COMPLETED/FAILED` event scopes.

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
- **Human Task (`humanTask` field, preferred):** A step that pauses the process and creates a structured `HumanTask` DB record for the task-inbox UI. The frontend discovers it via `GET /api/tasks` and completes it via `POST /api/tasks/{id}/complete`.
  - *Visualization:* Node with `UserCheck` icon, orange/amber color. Show `humanTask.taskName` as the node label.
  - *Detection:* Rule has `humanTask !== null`. The suspend step is `rule.next`.
- **Human Task (legacy `suspend: true`):** Raw suspend gate — no structured task record. Still supported but deprecated in favor of `humanTask`.
  - *Visualization:* Same orange/amber style as above but without a task name label.
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
  | "WAITING_FOR_CHILD"
  | "COMPENSATION_FAILED";

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
  | "WEBHOOK_FAILED"
  | "HUMAN_TASK_CREATED"
  | "HUMAN_TASK_COMPLETED"
  | "HUMAN_TASK_CANCELLED"
  | "COMPENSATION_FAILED"
  | "COMPENSATION_ACKNOWLEDGED";

type HumanTaskStatus = "PENDING" | "COMPLETED" | "CANCELLED";

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

interface CompleteTaskRequest {
  resultData: Record<string, unknown>; // form values submitted by the human
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
  compensationFailed: number; // processes stuck in COMPENSATION_FAILED awaiting manual ack
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

interface HumanTaskDefinition {
  taskName: string;              // display name in task list
  signalEvent: string;           // must match a transitions key; fired on task completion
  formSchema?: Record<string, unknown> | null; // dynamic form spec, e.g. { fields: [...] }
  assignee?: string | null;      // literal string or SpEL expression (starts with #)
}

interface HumanTaskResponse {
  id: string;                    // UUID
  processInstanceId: string;     // UUID of the parent process
  processDefinitionName: string;
  taskName: string;
  signalEvent: string;
  formSchema: Record<string, unknown> | null;
  assignee: string | null;
  status: HumanTaskStatus;
  createdAt: string;             // ISO LocalDateTime
  completedAt: string | null;
  resultData: Record<string, unknown> | null; // submitted form values
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
  humanTask?: HumanTaskDefinition | null; // first-class human task gate (preferred over suspend: true)
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
| **Human task** | `humanTask`, `next` | `condition` |

All fields not listed for a rule type will be `null`/absent. The `@JsonInclude(NON_NULL)` annotation means null fields are omitted from the JSON payload.

**`humanTask` vs `suspend: true`:** Both suspend the process. `humanTask` is the preferred, first-class mechanism — it additionally creates a `HumanTask` DB record that the task-inbox UI can discover and complete. `suspend: true` is the legacy raw-suspend mechanism that requires manual `POST /api/processes/{id}/signal` calls with no structured task data.

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
| **Cancel** | `RUNNING`, `STALLED`, `SUSPENDED`, `SCHEDULED`, `WAITING_FOR_CHILD`, `COMPENSATION_FAILED` | Cascades cancel to active child processes **and** auto-cancels all PENDING human tasks for this process |
| **Retry** | `FAILED`, `STALLED` | Resets to initial step |
| **Advance** | `RUNNING`, `STALLED` | Manually forces the next transition |
| **Wake** | `SCHEDULED` only | Bypasses remaining timer delay |
| **Signal** | `SUSPENDED` only | Injects event + data, evaluates transitions |
| **Replay** | Any non-`COMPLETED` | Rewinds to a historical step from audit trail |
| **Acknowledge Compensation Failure** | `COMPENSATION_FAILED` only | Marks process `CANCELLED` after operator manually fixes the DB |

**Implication for the UI:** Conditionally enable/disable action buttons based on the process `status`.

## 10. Complete Mock Data Reference

This section provides the exact JSON definitions for all 11 seeded flows plus mock data for process instances, audit trails, and webhooks. Use these to populate the MSW/mock layer.

### 10.1. All 11 Seeded Process Definitions

**Legend for flow graphs below:**
```
[STEP]            — Default Task (external worker)
[🌐 STEP]         — HTTP Task (native HTTP REST step)
[⏱ STEP]          — Timer / Delay Task
[👤 STEP]          — Human Task (first-class or legacy suspend)
[[STEP]]          — Sub-Process (call activity)
[≡ STEP]           — Multi-Instance (scatter-gather)
[↩ STEP]           — Compensation step (dashed in real UI)
◇                 — XOR Gateway (conditional branch)
⊕ FORK / ⊕ JOIN   — Parallel Fork / Join (AND gateway)
● START           — Start event
◉ COMPLETED       — Terminal end event
---->             — normal edge
- - ->            — compensation / dashed edge
```

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

**Flow Graph:**
```
● START ──> [STEP_1] ──> [STEP_2] ──> ◉ COMPLETED
```

#### LOAN_APPROVAL — Conditional branching + first-class human task gate + output mapping
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
      {
        "next": "MANUAL_REVIEW",
        "humanTask": {
          "taskName": "Manual Loan Review",
          "signalEvent": "APPROVAL_GRANTED",
          "formSchema": {
            "fields": [
              { "name": "approved", "type": "boolean", "label": "Approve?" },
              { "name": "notes",    "type": "string",  "label": "Notes" }
            ]
          },
          "assignee": null
        }
      }
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
*Note: When credit score ≤ 700 the process suspends at `MANUAL_REVIEW` **and** creates a `HumanTask` record. The task-inbox UI calls `POST /api/tasks/{taskId}/complete` with `{"resultData": {"approved": true}}` — the engine fires the `APPROVAL_GRANTED` signal automatically. The `approved` and `notes` fields from `resultData` are available as `#approved` / `#notes` in the SpEL conditions on `APPROVAL_GRANTED` rules.*

**Flow Graph:**
```
                                      ┌──> [AUTO_APPROVE] ──────────────────────────────────────┐
                                      │   (#creditScore > 700)                                  │
● START ──> [VALIDATE_CREDIT] ──> ◇ ──┤                                                         ├──> [DISBURSE_FUNDS] ──> ◉ COMPLETED
                                      │   (default)                                              │
                                      └──> [👤 MANUAL_REVIEW] ──> ◇ ──> [DISBURSE_FUNDS] ────────┘
                                            "Manual Loan Review"    │   (#approved == true)
                                            signal: APPROVAL_GRANTED└──> [SEND_REJECTION] ──> ◉ COMPLETED
```

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

**Flow Graph:**
```
● START ──> [VALIDATE_ORDER] ──> [RESERVE_INVENTORY] ──> ◇ ──────────────────────────> [PROCESS_PAYMENT] ──> ◇ ──────────────> [SHIP_ORDER] ──> ◉ COMPLETED
                                                          │ (#inventoryAvailable==true)                      │ (#paymentSuccess==true)
                                                          └──> [NOTIFY_OUT_OF_STOCK] ──> ◉ COMPLETED          └──> [NOTIFY_PAYMENT_FAILED] ──> ◉ COMPLETED
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

**Flow Graph:**
```
                                         ┌──> [VALIDATE_CREDIT] ──┐
● START ──> [PREPARE_APPLICATION] ──> ⊕ FORK                      ├──> ⊕ JOIN ──> [APPROVE_LOAN] ──> ◉ COMPLETED
                                         └──> [VERIFY_IDENTITY]  ──┘
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

**Flow Graph:**
```
● START ──> [RESERVE_INVENTORY] ──> [CHARGE_PAYMENT] ──> [SHIP_ORDER] ──> ◉ COMPLETED
                  ↑                        ↑
                  │ (on failure)           │ (on failure)
            - - -> [↩ UNDO_RESERVE]   - - -> [↩ REFUND_PAYMENT]
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

**Flow Graph:**
```
● START ──> [PREPARE_REQUEST] ──> [⏱ 3 000 ms] ──> [PROCESS_REQUEST] ──> ◉ COMPLETED
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

**Flow Graph:**
```
● START ──> [FETCH_CREDIT_REPORT] ──> [EVALUATE_SCORE] ──> ◉ COMPLETED
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

**Flow Graph:**
```
● START ──> [COLLECT_APPLICATION] ──> [[CREDIT_CHECK_SUB]] ──> [MAKE_DECISION] ──> ◉ COMPLETED
                                       (spawns child process,
                                        waits for completion)
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

**Flow Graph:**
```
                                       ┌──> [≡ PROCESS_ORDER (instance 0)] ──┐
● START ──> [RECEIVE_ORDERS] ──> ⊕ MI  ├──> [≡ PROCESS_ORDER (instance 1)] ──┤ ──> ⊕ JOIN ──> [SHIP_ORDERS] ──> ◉ COMPLETED
                                       └──> [≡ PROCESS_ORDER (instance N)] ──┘
                                            (fan-out over orderItems[])
```

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

**Flow Graph:**
```
● START ──> [STEP_1] ──> [🌐 GET jsonplaceholder.typicode.com/todos/1] ──> [STEP_2] ──> ◉ COMPLETED
```

#### HUMAN_TASK_FLOW — First-class human task gate
```json
{
  "name": "HUMAN_TASK_FLOW",
  "version": 1,
  "initialStep": "SUBMIT",
  "transitions": {
    "SUBMIT_FINISHED": [
      {
        "next": "REVIEW_TASK",
        "humanTask": {
          "taskName": "Review Submission",
          "signalEvent": "REVIEW_APPROVED",
          "formSchema": {
            "fields": [
              { "name": "approved", "type": "boolean", "label": "Approve?" },
              { "name": "comment",  "type": "string",  "label": "Comment" }
            ]
          },
          "assignee": null
        }
      }
    ],
    "REVIEW_APPROVED": [{ "next": "COMPLETE" }],
    "COMPLETE_FINISHED": [{ "next": "COMPLETED" }]
  },
  "compensations": {}
}
```
*Start with: `{"definitionName": "HUMAN_TASK_FLOW", "initialData": {"submitter": "alice"}}`. After SUBMIT finishes, a `HumanTask` record is created. Call `POST /api/tasks/{taskId}/complete` with `{"resultData": {"approved": true, "comment": "LGTM"}}` to resume.*

**Flow Graph:**
```
● START ──> [SUBMIT] ──> [👤 REVIEW_TASK] ──> [COMPLETE] ──> ◉ COMPLETED
                         "Review Submission"
                          signal: REVIEW_APPROVED
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
  },
  {
    id: "a1b2c3d4-0001-0001-0001-000000000011",
    definitionName: "PAYMENT_SAGA",
    definitionVersion: 1,
    currentStep: "REFUND_PAYMENT",
    status: "COMPENSATION_FAILED",
    createdAt: "2026-03-11T09:45:00",
    stepStartedAt: "2026-03-11T09:45:30",
    completedAt: null,
    contextData: "{\"error\": \"Refund gateway unreachable\"}",
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
  total: 151,
  running: 12,
  completed: 118,
  failed: 5,
  stalled: 2,
  cancelled: 8,
  scheduled: 3,
  waitingForChild: 2,
  compensationFailed: 1,
  successRate: 78.15
};
```

### 10.5. Mock Human Tasks

```typescript
const mockHumanTasks: HumanTaskResponse[] = [
  {
    id: "ht-0001-0001-0001-000000000001",
    processInstanceId: "a1b2c3d4-0001-0001-0001-000000000004",
    processDefinitionName: "LOAN_APPROVAL",
    taskName: "Manual Loan Review",
    signalEvent: "APPROVAL_GRANTED",
    formSchema: {
      fields: [
        { name: "approved", type: "boolean", label: "Approve?" },
        { name: "notes",    type: "string",  label: "Notes" }
      ]
    },
    assignee: null,
    status: "PENDING",
    createdAt: "2026-03-11T09:10:03",
    completedAt: null,
    resultData: null
  },
  {
    id: "ht-0001-0001-0001-000000000002",
    processInstanceId: "a1b2c3d4-0001-0001-0001-000000000011",
    processDefinitionName: "HUMAN_TASK_FLOW",
    taskName: "Review Submission",
    signalEvent: "REVIEW_APPROVED",
    formSchema: {
      fields: [
        { name: "approved", type: "boolean", label: "Approve?" },
        { name: "comment",  type: "string",  label: "Comment" }
      ]
    },
    assignee: null,
    status: "PENDING",
    createdAt: "2026-03-11T10:00:00",
    completedAt: null,
    resultData: null
  },
  {
    id: "ht-0001-0001-0001-000000000003",
    processInstanceId: "a1b2c3d4-0001-0001-0001-000000000002",
    processDefinitionName: "LOAN_APPROVAL",
    taskName: "Manual Loan Review",
    signalEvent: "APPROVAL_GRANTED",
    formSchema: {
      fields: [
        { name: "approved", type: "boolean", label: "Approve?" },
        { name: "notes",    type: "string",  label: "Notes" }
      ]
    },
    assignee: null,
    status: "COMPLETED",
    createdAt: "2026-03-11T08:31:00",
    completedAt: "2026-03-11T08:31:45",
    resultData: { approved: true, notes: "Credit history looks good" }
  }
];
```

### 10.6. Mock Webhook Subscriptions

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
- If a rule with `humanTask !== null` targets this step → **Human Task** node (first-class; display `humanTask.taskName`)
- If a rule with `suspend: true` (and no `humanTask`) targets this step → **Human Task** node (legacy)
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
| `COMPENSATION_FAILED` status | Red border on current compensation step + amber banner: "Compensation failed — operator action required" |

To determine which steps are completed, use the audit trail: filter for `STEP_TRANSITION` events and collect all `stepName` values — those are the steps that were successfully entered.
