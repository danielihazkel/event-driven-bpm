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
      "name": "LOAN_APPROVAL",
      "version": 1,
      "initialStep": "VALIDATE_CREDIT",
      "transitions": { ... },
      "compensations": { "RESERVE_INVENTORY": "UNDO_RESERVE_INVENTORY" },
      "createdAt": "2026-03-11T09:00:00Z"
    }
  ]
  ```

**Get Single Definition**
- **Endpoint:** `GET /api/definitions/{name}`
- **Response:** Single `ProcessDefinitionResponse` object.

**Create/Update Definition**
- **Endpoint:** `POST /api/definitions` (Create) or `PUT /api/definitions/{name}` (Update Version)
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
- **Endpoint:** `GET /api/processes?status={status}&definitionName={name}&page=0&size=20`
- **Response (Paginated Data):**
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
        "createdAt": "2026-03-11T09:01:00Z"
      }
    ],
    "totalPages": 1,
    "totalElements": 1
  }
  ```
  *(Possible Statuses: `RUNNING`, `COMPLETED`, `FAILED`, `SUSPENDED`, `STALLED`, `CANCELLED`, `SCHEDULED`, `WAITING_FOR_CHILD`)*

**Get Process Status**
- **Endpoint:** `GET /status/{id}`
- **Response:**
  ```json
  {
    "processId": "550e8400-...",
    "status": "RUNNING",
    "step": "STEP_1",
    "context": "{\"userId\": 42}"
  }
  ```

**Get Audit Trail**
- **Endpoint:** `GET /api/processes/{id}/audit`
- **Response `AuditLogResponse[]`:**
  ```json
  [
    {
      "id": 1,
      "eventType": "PROCESS_STARTED",
      "step": "VALIDATE_CREDIT",
      "message": "Process started",
      "contextSnapshot": "{\"creditScore\": 750}",
      "timestamp": "2026-03-11T09:00:00Z"
    }
  ]
  ```

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
    "totalProcesses": 100,
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

### 4.1. Dashboard (`/`)
- **Purpose:** At-a-glance view of system health and quick navigational shortcuts.
- **Visuals & Components:**
  - **Metric Cards Grid:** Top row displaying total processes, running, completed, failed, stalled, and overall success rate (fetched from `/api/metrics/summary`).
  - **Quick Actions Bar:** "Start New Flow" and "Create Definition" prominent buttons.
  - **Recent Activity Table:** Top 5-10 most recently started or failed instances to alert the user of immediate issues.

### 4.2. Process Definitions (`/definitions`)
- **Purpose:** Manage, explore, and configure workflow schemas.
- **Visuals & Components:**
  - **Data Table:** List of definitions showing Name, Latest Version, Created At, and Actions (Edit, Delete, Start).
  - **Definition Editor View (`/definitions/:name`):**
    - **Header:** Definition name badge, Version selector (to view older versions), and Action buttons (Save New Version, Delete).
    - **Split Pane / Tabs:**
      - **Visual Mode (React Flow):** Canvas displaying step nodes and edges based on `transitions_json`. Custom nodes for Fork, Join, Suspend, HttpRequest, and Delay steps.
      - **JSON Mode (Monaco):** Editor to manually view or edit definition transition rules.

### 4.3. Process Instances / Monitoring (`/instances`)
- **Purpose:** Track real-time execution of processes across the system.
- **Visuals & Components:**
  - **Command Bar / Filters:** Dropdowns to filter by `definitionName` and `status` (RUNNING, FAILED, STALLED, SUSPENDED, SCHEDULED, WAITING_FOR_CHILD, etc.). Search by ID.
  - **Data Table:** ID, Definition, Status (colored badges), Current Step, Started At, and Completed At. Includes server-side pagination controls.

### 4.4. Process Detail View (`/instances/:id`)
- **Purpose:** Deep dive into a single process execution, troubleshooting, and manual administrative intervention.
- **Visuals & Components:**
  - **Header Ribbon:** Status badge, Process ID, timestamps. Administrative Action buttons: Cancel, Retry, Advance, Force Wake (if SCHEDULED), Signal (if SUSPENDED).
  - **Split Pane Layout:**
    - **Left Side (Visual Tracker):** React Flow graph of the definition, with the `current_step` highlighted with a glowing pulse, completed steps marked green, and failed steps marked red.
    - **Right Side (Tabs):**
      1. **Context/Data:** Read-only generic JSON viewer for `contextData`.
      2. **Audit Trail:** Vertical timeline (using Tailwind standard timeline UI) decoding events from `/api/processes/{id}/audit`. 
      3. **Advanced State:** View specialized database fields like `parallelPending`, `joinStep`, and `compensating` states.
  - **Intervention Modals:**
    - **Signal Modal:** Form requiring an Event Name and JSON data payload to resume a `SUSPENDED` process.
    - **Replay/Time-Travel Modal:** Modal that fetches the audit trail, presents a timeline of viable historical steps, and exposes a "Replay from Here" button to call `/api/processes/{id}/replay?fromStep=X`.

### 4.5. Webhooks Management (`/webhooks`)
- **Purpose:** Manage subscriptions to terminal state events for system integrations.
- **Visuals & Components:**
  - **Data Table:** Webhook URL, targeted events, definition filters, and an Active/Inactive toggle switch (`/api/webhooks/{id}/toggle`).
  - **Slide-out Panel / Modal:** Form to create a new subscription (target URL, multi-select for events, optional secret).

## 6. Detailed Task Breakdown (Iterative To-Do List)

The execution will follow atomic, actionable tasks to maintain high velocity and manageable PRs.

### Sprint 1: Project Initialization & Infrastructure
- [ ] **1.1:** Initialize the project using `npm create vite@latest edoe-frontend -- --template react-ts`.
- [ ] **1.2:** Install and configure `tailwindcss`, `postcss`, and `autoprefixer`. Initialize `tailwind.config.js`.
- [ ] **1.3:** Setup absolute path aliases (e.g., `@/` maps to `./src/`) in `vite.config.ts` and `tsconfig.json`.
- [ ] **1.4:** Install essential core libraries: `react-router-dom`, `@tanstack/react-query`, `lucide-react`, `axios`, `clsx`, `tailwind-merge`.
- [ ] **1.5:** Run `npx shadcn-ui@latest init` to establish base design tokens and theme variables (CSS variables for dark/light modes).
- [ ] **1.6:** Setup an Axios instance/interceptor with a configurable base URL (`import.meta.env.VITE_API_URL`) and global error handling.

### Sprint 2: Core Layout & Routing Shell
- [ ] **2.1:** Develop the `Sidebar` component mapping out primary navigation links (`/`, `/definitions`, `/instances`, `/webhooks`).
- [ ] **2.2:** Develop the `TopBar` component featuring breadcrumb navigation and a Dark/Light Theme Toggle.
- [ ] **2.3:** Create the `AppShell` layout wrapper that coordinates the Sidebar, TopBar, and `<Outlet />`.
- [ ] **2.4:** Configure React Router in `App.tsx` defining standard layout routes and lazy-loaded page components for performance.
- [ ] **2.5:** Add `shadcn/ui` foundational components: Button, Input, Card, Table, Dialog, Sheet, and Toaster (Sonner).

### Sprint 3: API Integration, Mocking & Dashboard
- [ ] **3.1:** Create `types/api.ts` mapping all backend DTOs (e.g., `ProcessInstanceResponse`, `MetricsSummaryResponse`, `TransitionRule`).
- [ ] **3.2:** Implement an API Adapter or MSW (Mock Service Worker) configuration (`VITE_USE_MOCK_DATA=true`).
- [ ] **3.3:** Populate the mock definition store with the 10 example flows from the backend's `README.md` (e.g., `DEFAULT_FLOW`, `LOAN_APPROVAL`, `ORDER_FULFILLMENT`, `PARALLEL_FLOW`, `PAYMENT_SAGA`, `DELAY_FLOW`, `CREDIT_CHECK_SUB`, `SUB_PROCESS_FLOW`, `SCATTER_GATHER_FLOW`, `HTTP_STEP_FLOW`).
- [ ] **3.4:** Create mock responses for metrics (`/api/metrics/summary`) and recent process instances to support dashboard development without the backend.
- [ ] **3.5:** Write custom React Query hooks for fetching metrics (`useMetricsSummary`) that pass through the API Adapter.
- [ ] **3.6:** Implement Dashboard metrics cards mapping data to dynamic Lucide icons and colors based on health/status.
- [ ] **3.7:** Write custom hooks for fetching recent process instances.
- [ ] **3.8:** Build the Dashboard Activity Table to display recent runs.

### Sprint 4: Process Definitions & Visualizer Setup
- [ ] **4.1:** Write custom hooks (`useDefinitions`, `useCreateDefinition`, `useDeleteDefinition`).
- [ ] **4.2:** Build the Definitions page with a data table and "Create New" context menu.
- [ ] **4.3:** Install `@monaco-editor/react`. Build a reusable `JsonEditor` component for editing definition rules.
- [ ] **4.4:** Build the "Start Flow" modal dialog (Definition selector, Version input, Monaco editor for `initialData`).
- [ ] **4.5:** Install `reactflow`. Write an adapter parser function to transform EDOE `transitions_json` rules (conditonal, parallel, sub-process) into React Flow `Node[]` and `Edge[]`.
- [ ] **4.6:** Develop the `DefinitionVisualizer` component rendering the parsed node graph.

### Sprint 5: Process Monitoring & Detail View
- [ ] **5.1:** Write hooks (`useProcessInstances` with pagination and filter arguments).
- [ ] **5.2:** Build the Instances table supporting backend pagination, definition dropdown filtering, and status filtering.
- [ ] **5.3:** Build the Process Detail outer shell (`/instances/:id`) incorporating the Header Ribbon and dynamic Status Badges.
- [ ] **5.4:** Implement automatic data refetching/polling (e.g., `refetchInterval: 3000`) for instances still in `RUNNING`, `SCHEDULED`, or `WAITING_FOR_CHILD`.
- [ ] **5.5:** Embed the `DefinitionVisualizer` inside the Process Detail page. Enhance the adapter parser to apply specific styling (CSS classes/Tailwind) to the `current_step` node and failed nodes.

### Sprint 6: Audit Trail & Complex Interventions
- [ ] **6.1:** Write the `useAuditTrail` query hook.
- [ ] **6.2:** Develop an `AuditTimeline` custom component leveraging Tailwind CSS borders/circles to visualize state changes and `contextSnapshot` payloads beautifully.
- [ ] **6.3:** Build reusable JSON context viewer tab for real-time process state inspection.
- [ ] **6.4:** Implement intervention mutation hooks: `useCancelProcess`, `useRetryProcess`, `useAdvanceProcess`, `useWakeProcess`.
- [ ] **6.5:** Hook administrative mutation buttons in the Header Ribbon to their respective functions, including confirmation alerts.
- [ ] **6.6:** Build and integrate the "Signal Modal" form (Event Type input, Data JSON input) calling `/api/processes/{id}/signal`.
- [ ] **6.7:** Build the "Replay / Time-Travel Modal". Pull the audit trail, allow the user to select an eligible `PROCESS_STARTED` or `STEP_TRANSITION` event, and submit to `/api/processes/{id}/replay?fromStep=X`.

### Sprint 7: Webhooks & Final Polish
- [ ] **7.1:** Write Webhook CRUD and Toggle query/mutation hooks.
- [ ] **7.2:** Build the Webhooks management table displaying active states and subscribed events.
- [ ] **7.3:** Build the Webhook creation Slide-out Panel (URL input, Event Multi-select using a custom combobox or checkboxes, Secret input).
- [ ] **7.4:** Implement Empty States and Loading Skeletons (`shadcn/ui` Skeleton) across all tables and dashboard widgets.
- [ ] **7.5:** Audit responsive layouts to ensure the frontend works effectively on smaller laptop screens or tablets.
- [ ] **7.6:** Final QA, console warning cleanups, and deploy configuration settings (e.g., multi-environment `env` support).
