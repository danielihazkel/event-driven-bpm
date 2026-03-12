# Frontend To-Do List

## Sprint 1: Project Initialization & Infrastructure
- [ ] **1.1:** Initialize the project using `npm create vite@latest edoe-frontend -- --template react-ts`.
- [ ] **1.2:** Create folder structure: `src/{components,pages,hooks,lib,types,mocks}` with barrel `index.ts` files where appropriate.
- [ ] **1.3:** Install and configure `tailwindcss`, `postcss`, and `autoprefixer`. Initialize `tailwind.config.js`.
- [ ] **1.4:** Setup absolute path aliases (`@/` → `./src/`) in `vite.config.ts` and `tsconfig.json`.
- [ ] **1.5:** Install core libraries: `react-router-dom`, `@tanstack/react-query`, `lucide-react`, `axios`, `clsx`, `tailwind-merge`.
- [ ] **1.6:** Run `npx shadcn-ui@latest init` to establish base design tokens and CSS variables for dark/light modes.
- [ ] **1.7:** Create `.env` and `.env.example` with `VITE_API_URL=http://localhost:8080` and `VITE_USE_MOCK_DATA=true`.
- [ ] **1.8:** Configure Vite dev proxy in `vite.config.ts` to forward `/api` requests to `VITE_API_URL` (avoids CORS in local dev).
- [ ] **1.9:** Setup an Axios instance in `lib/api-client.ts` with configurable base URL (`import.meta.env.VITE_API_URL`) and global error handling (toast on 4xx/5xx).
- [ ] **1.10:** Implement JWT auth in `lib/api-client.ts`: attach `Authorization: Bearer <token>` header from `localStorage.getItem("edoe_token")` on every request; on 401 clear token and redirect to `/login`; on 403 show "Insufficient permissions" toast.
- [ ] **1.11:** Build a minimal `/login` page with a token text input (for dev: instructions to hit `GET /dev/token`). Store the submitted token in `localStorage` and redirect to `/`.

## Sprint 2: Core Layout, Routing & Design System Shell
- [ ] **2.1:** Add `shadcn/ui` components needed for layout: `Button`, `Input`, `Card`.
- [ ] **2.2:** Add `shadcn/ui` data components: `Table`, `Dialog`, `Sheet`.
- [ ] **2.3:** Add `shadcn/ui` feedback components: install `sonner`, add `Toaster` provider in `App.tsx`.
- [ ] **2.4:** Install `@tanstack/react-query-devtools`. Add `ReactQueryDevtools` to `App.tsx` (dev-only).
- [ ] **2.5:** Develop the `Sidebar` component with navigation links: `/` (Dashboard), `/definitions`, `/instances`, `/tasks`, `/webhooks`.
- [ ] **2.6:** Develop the `TopBar` component with breadcrumb navigation and Dark/Light theme toggle.
- [ ] **2.7:** Create the `AppShell` layout wrapper that coordinates `Sidebar`, `TopBar`, and `<Outlet />`.
- [ ] **2.8:** Configure React Router in `App.tsx` with layout routes and lazy-loaded page components (`React.lazy`).

## Sprint 3: TypeScript Types, Mock Data & API Layer
- [ ] **3.1:** Create `types/api.ts` mapping all backend DTOs: `ProcessInstanceResponse`, `ProcessDefinitionResponse`, `MetricsSummaryResponse`, `TransitionRule`, `StartFlowRequest`, `SignalRequest`, `AuditLogResponse`, `HumanTaskResponse`, `HumanTaskDefinition`, `CompleteTaskRequest`, `WebhookSubscriptionResponse`, `WebhookSubscriptionRequest`, `ProcessStatus` (must include `COMPENSATION_FAILED`), `HumanTaskStatus`, `AuditEventType` (must include `COMPENSATION_FAILED` and `COMPENSATION_ACKNOWLEDGED`). `MetricsSummaryResponse` must include `compensationFailed: number`.
- [ ] **3.2:** Implement the API adapter pattern: create `lib/api-adapter.ts` that checks `VITE_USE_MOCK_DATA` and routes calls to either real Axios endpoints or local mock stores. Export typed functions per endpoint (e.g., `getDefinitions()`, `getInstances()`, `getMetrics()`).
- [ ] **3.3:** Populate mock definition store (`mocks/definitions.ts`) with all 11 seed flows from `DataInitializer` (`DEFAULT_FLOW`, `LOAN_APPROVAL`, `ORDER_FULFILLMENT`, `PARALLEL_FLOW`, `PAYMENT_SAGA`, `DELAY_FLOW`, `CREDIT_CHECK_SUB`, `SUB_PROCESS_FLOW`, `SCATTER_GATHER_FLOW`, `HTTP_STEP_FLOW`, `HUMAN_TASK_FLOW`). Include full `transitionsJson` and `compensationsJson` per flow.
- [ ] **3.4:** Populate mock instance store (`mocks/instances.ts`) with sample `ProcessInstanceResponse` entries in various statuses (RUNNING, COMPLETED, FAILED, SUSPENDED, STALLED, CANCELLED, SCHEDULED, WAITING_FOR_CHILD, COMPENSATION_FAILED). Include parent/child relationships for sub-process instances.
- [ ] **3.5:** Populate mock human task store (`mocks/tasks.ts`) with sample `HumanTaskResponse` entries (PENDING and COMPLETED) including `formSchema` fields of varying types (string, boolean, number, select).
- [ ] **3.6:** Create mock responses for metrics (`/api/metrics/summary`) and audit trail (`/api/processes/{id}/audit`) in `mocks/metrics.ts` and `mocks/audit.ts`. Include `compensationFailed` field in mock metrics.

## Sprint 4: Dashboard Page
- [ ] **4.1:** Write React Query hook `useMetricsSummary` calling `GET /api/metrics/summary` through the API adapter.
- [ ] **4.2:** Write React Query hook `useRecentInstances` calling `GET /api/processes?page=0&size=10` through the API adapter.
- [ ] **4.3:** Build Dashboard metrics cards: map each status count from `MetricsSummaryResponse` to a card with dynamic Lucide icon, color, and label. Include `successRate` as a percentage card. Add a `compensationFailed` card (orange/amber, `AlertTriangle` icon) that only shows when count > 0 — clicking it filters instances to `COMPENSATION_FAILED`.
- [ ] **4.4:** Build Dashboard Activity Table showing recent process instances with columns: definition name, current step, status badge, created time.
- [ ] **4.5:** Add "Start New Flow" button on Dashboard that opens the Start Flow modal (built in Sprint 5).

## Sprint 5: Process Definitions Page & Editor
- [ ] **5.1:** Write React Query hooks: `useDefinitions` (`GET /api/definitions`), `useDefinition(name)` (`GET /api/definitions/{name}`).
- [ ] **5.2:** Write mutation hook `useCreateDefinition` (`POST /api/definitions`) with toast on success/error, invalidates `useDefinitions`.
- [ ] **5.3:** Write mutation hook `useUpdateDefinition` (`PUT /api/definitions/{name}`) for saving edits — creates a new version. Toast + invalidate on success.
- [ ] **5.4:** Write mutation hook `useDeleteDefinition` (`DELETE /api/definitions/{name}`) with invalidation.
- [ ] **5.5:** Build the Definitions list page with a data table (columns: name, version, initial step, created date) and a "Create New" button.
- [ ] **5.6:** Build the Delete Definition confirmation dialog — triggered from a row action menu on the Definitions table.
- [ ] **5.7:** Install `@monaco-editor/react`. Build a reusable `JsonEditor` component wrapping Monaco with JSON schema validation and dark mode support.
- [ ] **5.8:** Build the Definition Editor page (`/definitions/:name`): displays current version info, `JsonEditor` for `transitionsJson` and `compensationsJson`, and a "Save" button that calls `useUpdateDefinition`.
- [ ] **5.9:** Add a version selector dropdown on the Definition Editor page — fetches version history and allows viewing/editing older versions.
- [ ] **5.10:** Build the "Start Flow" modal dialog: definition dropdown selector, optional version number input, Monaco editor for `initialData` JSON, and submit button calling `POST /start-flow`.

## Sprint 6: Visualizer — Parser & Custom Nodes
- [ ] **6.1:** Install `reactflow`. Create `lib/parser/types.ts` defining internal node/edge types for the visualizer (e.g., `FlowNode`, `FlowEdge`, `NodeType` enum).
- [ ] **6.2:** Write parser step 1 — **linear chains**: transform a simple `transitions` map (each step has one unconditional `TransitionRule.of()`) into `Node[]` and `Edge[]`. Include START and END sentinel nodes.
- [ ] **6.3:** Write parser step 2 — **conditional branches**: handle steps with multiple `TransitionRule` entries that have SpEL `condition` strings. Render as a diamond decision node with labeled edges per condition.
- [ ] **6.4:** Write parser step 3 — **parallel fork/join**: detect `TransitionRule.fork()` rules (non-null `parallel` array + `joinStep`). Render fork node splitting into N parallel branches converging at the join step.
- [ ] **6.5:** Write parser step 4 — **advanced patterns**: handle `callActivity` (sub-process call node), `delayMs` (timer node), `multiInstanceVariable` (scatter-gather node), `httpRequest` (HTTP step node), `humanTask` (human task node), `suspend` (approval gate node).
- [ ] **6.6:** Write parser step 5 — **compensation edges**: parse `compensationsJson` map and render dashed reverse edges from each step to its compensation step.
- [ ] **6.7:** Build custom React Flow node components: `StandardNode`, `DecisionNode` (diamond), `ForkNode`, `JoinNode`, `TimerNode`, `HttpNode`, `SubProcessNode`, `HumanTaskNode`, `StartNode`, `EndNode`. Each with appropriate icon (Lucide) and color coding.
- [ ] **6.8:** Build the `DefinitionVisualizer` canvas component: renders React Flow with custom nodes, auto-layout (dagre or elkjs), zoom controls, and minimap. Accepts parsed `Node[]`/`Edge[]` as props.
- [ ] **6.9:** Embed `DefinitionVisualizer` on the Definition Editor page below the JSON editor, updating live as JSON changes.

## Sprint 7: Process Instances List & Detail View
- [ ] **7.1:** Write React Query hook `useProcessInstances` with pagination (`page`, `size`) and filter params (`definitionName`, `status`).
- [ ] **7.2:** Build the Instances list page with data table, pagination controls, definition dropdown filter, and status filter chips.
- [ ] **7.3:** Add a "Start New Flow" button on the Instances page that opens the same Start Flow modal from Sprint 5.
- [ ] **7.4:** Build the Process Detail page header ribbon (`/instances/:id`): display definition name, version, process ID, and created timestamp.
- [ ] **7.5:** Build dynamic status badge component mapping each `ProcessStatus` to a color and icon (e.g., RUNNING → blue spinner, FAILED → red X, SUSPENDED → amber pause, COMPENSATION_FAILED → orange `AlertTriangle`).
- [ ] **7.6:** Build parent/child navigation links on the Detail page: if `parentProcessId` is set, show "Parent Process" link; query `GET /api/processes` filtered by parent to list child processes.
- [ ] **7.7:** Add step duration display: calculate elapsed time from `stepStartedAt` to now (if RUNNING) or to `completedAt` (if terminal).
- [ ] **7.8:** Implement auto-polling: set `refetchInterval: 3000` on `useProcessInstance(id)` when status is `RUNNING`, `SCHEDULED`, or `WAITING_FOR_CHILD`.
- [ ] **7.9:** Build a "Context Data" tab on the Detail page: parse `contextData` string with `JSON.parse()` (backend returns a JSON-encoded string, not an object) and render in a collapsible JSON tree viewer or Monaco read-only editor.
- [ ] **7.10:** Embed `DefinitionVisualizer` on the Process Detail page with execution highlighting: apply distinct CSS classes to the `currentStep` node (e.g., green pulse for RUNNING, red for FAILED). Strip `__MI__\d+` suffixes before matching multi-instance steps.

## Sprint 8: Audit Trail & Process Interventions
- [ ] **8.1:** Write React Query hook `useAuditTrail(processId)` calling `GET /api/processes/{id}/audit`.
- [ ] **8.2:** Build `AuditTimeline` component: vertical timeline using Tailwind CSS borders/circles, displaying each audit event with timestamp, `eventType` badge, and expandable `payload`/`contextSnapshot` JSON viewer.
- [ ] **8.3:** Render human-task audit events (`HUMAN_TASK_CREATED`, `HUMAN_TASK_COMPLETED`, `HUMAN_TASK_CANCELLED`) with a `UserCheck` icon and task name from the audit payload. Render `COMPENSATION_FAILED` with a red `AlertTriangle` icon and `COMPENSATION_ACKNOWLEDGED` with a green `CheckCircle` icon.
- [ ] **8.4:** Write mutation hook `useCancelProcess` — `POST /api/processes/{id}/cancel`, invalidates instance query, shows toast.
- [ ] **8.5:** Write mutation hook `useRetryProcess` — `POST /api/processes/{id}/retry`, invalidates instance query, shows toast.
- [ ] **8.6:** Write mutation hook `useAdvanceProcess` — `POST /api/processes/{id}/advance?toStep=`, invalidates instance query.
- [ ] **8.7:** Write mutation hook `useWakeProcess` — `POST /api/processes/{id}/wake`, invalidates instance query.
- [ ] **8.8:** Wire intervention buttons into the Detail page header ribbon: Cancel (with confirmation dialog), Retry, Advance (step input), Wake. Show/hide buttons based on current process status (e.g., Cancel only for RUNNING/SUSPENDED/WAITING_FOR_CHILD/COMPENSATION_FAILED). When status is `COMPENSATION_FAILED`, show an amber banner "⚠ Compensation failed — manual DB remediation required" and an "Acknowledge & Cancel" button.
- [ ] **8.11:** Write mutation hook `useAcknowledgeCompensationFailure(id)` — `POST /api/processes/{id}/acknowledge-compensation-failure`, invalidates instance query, shows toast "Process acknowledged and cancelled". Only render the button when status is `COMPENSATION_FAILED`.
- [ ] **8.9:** Build the "Signal Modal": form with Event Type text input and Data JSON editor (Monaco). Submits `POST /api/processes/{id}/signal` with `{ event, data }`. Only available when status is `SUSPENDED`.
- [ ] **8.10:** Build the "Replay / Time-Travel Modal": load audit trail, filter to `PROCESS_STARTED` and `STEP_TRANSITION` events, let user select a step, and submit `POST /api/processes/{id}/replay?fromStep=X`. Show confirmation warning.

## Sprint 9: Human Task Inbox
- [ ] **9.1:** Write React Query hooks: `useHumanTasks(status?, assignee?)` with `refetchInterval: 10000`, `useHumanTask(id)`.
- [ ] **9.2:** Write mutation hooks: `useCompleteTask(id)` (`POST /api/tasks/{id}/complete`), `useCancelTask(id)` (`POST /api/tasks/{id}/cancel`). Both invalidate `useHumanTasks` on success and show toast.
- [ ] **9.3:** Build the Task Inbox left panel (`/tasks`): filterable task list with columns for `taskName`, `processDefinitionName`, `assignee`, `createdAt`, and `PENDING`/`COMPLETED` status badge. Add `?status=` and `?assignee=` filter controls.
- [ ] **9.4:** Build the Task Inbox right panel: when a task is selected, show task details and the dynamic form (or a "Completed" summary if already done).
- [ ] **9.5:** Build the `DynamicTaskForm` component: reads `formSchema.fields` and renders per-type inputs (`boolean` → checkbox, `string` → text input, `number` → number input, `select` → dropdown from `options`). On submit, collects values into `resultData` and calls `useCompleteTask`.
- [ ] **9.6:** Add sidebar badge on `/tasks` nav link showing pending task count from `useHumanTasks({ status: "PENDING" })`.
- [ ] **9.7:** On Process Detail page: when `status === "SUSPENDED"`, show a "View Task" link that queries pending tasks filtered by `processInstanceId` and navigates to `/tasks` with that task pre-selected.

## Sprint 10: Webhooks & Final Polish
- [ ] **10.1:** Write React Query hooks: `useWebhooks` (`GET /api/webhooks`), `useCreateWebhook` (`POST /api/webhooks`), `useDeleteWebhook` (`DELETE /api/webhooks/{id}`), `useToggleWebhook` (`PATCH /api/webhooks/{id}/toggle`).
- [ ] **10.2:** Build the Webhooks list page with data table showing URL, subscribed events, active toggle switch, and delete action.
- [ ] **10.3:** Build the Webhook creation slide-out panel (`Sheet`): URL input, event multi-select checkboxes (`COMPLETED`, `FAILED`, `CANCELLED`), optional secret input.
- [ ] **10.4:** Implement empty states across all list pages: show a centered illustration + message + CTA button when no data exists (e.g., "No definitions yet — Create one").
- [ ] **10.5:** Implement loading skeletons (`shadcn/ui Skeleton`) for: Dashboard metric cards, all data tables, Process Detail header, Audit Timeline.
- [ ] **10.6:** Audit responsive layouts: ensure Sidebar collapses to icons on screens < 1024px, tables scroll horizontally on narrow viewports, modals/sheets are full-width on mobile.
- [ ] **10.7:** Final QA: clear console warnings, verify all toast messages fire correctly, test dark/light mode across all pages, review accessibility (keyboard nav, aria labels on interactive elements).
- [ ] **10.8:** Production build & deploy config: multi-environment `.env` support (`.env.production`, `.env.staging`), verify `vite build` output.
- [ ] **10.9:** Implement role-aware UI rendering: detect `ROLE_ADMIN` vs `ROLE_VIEWER` from the decoded JWT (`jwt-decode` library). Hide all mutating buttons (Create, Edit, Delete, Cancel, Retry, Advance, Signal, Acknowledge) for VIEWER-only tokens. Show a "Read-only mode" chip in the TopBar when role is VIEWER.
- [ ] **10.10:** Add a dev-only "Get Token" helper panel (only renders when `VITE_USE_MOCK_DATA=false` and hostname is `localhost`): two buttons "Admin Token" / "Viewer Token" that call `GET /dev/token?role=ROLE_ADMIN` / `GET /dev/token?role=ROLE_VIEWER`, store the result and refresh the page.
