# Frontend To-Do List

## Sprint 1: Project Initialization & Infrastructure
- [ ] **1.1:** Initialize the project using `npm create vite@latest edoe-frontend -- --template react-ts`.
- [ ] **1.2:** Install and configure `tailwindcss`, `postcss`, and `autoprefixer`. Initialize `tailwind.config.js`.
- [ ] **1.3:** Setup absolute path aliases (e.g., `@/` maps to `./src/`) in `vite.config.ts` and `tsconfig.json`.
- [ ] **1.4:** Install essential core libraries: `react-router-dom`, `@tanstack/react-query`, `lucide-react`, `axios`, `clsx`, `tailwind-merge`.
- [ ] **1.5:** Run `npx shadcn-ui@latest init` to establish base design tokens and theme variables (CSS variables for dark/light modes).
- [ ] **1.6:** Setup an Axios instance/interceptor with a configurable base URL (`import.meta.env.VITE_API_URL`) and global error handling.

## Sprint 2: Core Layout & Routing Shell
- [ ] **2.1:** Develop the `Sidebar` component mapping out primary navigation links (`/`, `/definitions`, `/instances`, `/tasks`, `/webhooks`).
- [ ] **2.2:** Develop the `TopBar` component featuring breadcrumb navigation and a Dark/Light Theme Toggle.
- [ ] **2.3:** Create the `AppShell` layout wrapper that coordinates the Sidebar, TopBar, and `<Outlet />`.
- [ ] **2.4:** Configure React Router in `App.tsx` defining standard layout routes and lazy-loaded page components for performance.
- [ ] **2.5:** Add `shadcn/ui` foundational components: Button, Input, Card, Table, Dialog, Sheet, and Toaster (Sonner).

## Sprint 3: API Integration, Mocking & Dashboard
- [ ] **3.1:** Create `types/api.ts` mapping all backend DTOs (e.g., `ProcessInstanceResponse`, `MetricsSummaryResponse`, `TransitionRule`).
- [ ] **3.2:** Implement an API Adapter or MSW (Mock Service Worker) configuration (`VITE_USE_MOCK_DATA=true`).
- [ ] **3.3:** Populate the mock definition store with the 11 example flows from the backend's `README.md` (e.g., `DEFAULT_FLOW`, `LOAN_APPROVAL`, `ORDER_FULFILLMENT`, `PARALLEL_FLOW`, `PAYMENT_SAGA`, `DELAY_FLOW`, `CREDIT_CHECK_SUB`, `SUB_PROCESS_FLOW`, `SCATTER_GATHER_FLOW`, `HTTP_STEP_FLOW`, `HUMAN_TASK_FLOW`). Also populate the mock human task store using the data from section 10.5.
- [ ] **3.4:** Create mock responses for metrics (`/api/metrics/summary`) and recent process instances to support dashboard development without the backend.
- [ ] **3.5:** Write custom React Query hooks for fetching metrics (`useMetricsSummary`) that pass through the API Adapter.
- [ ] **3.6:** Implement Dashboard metrics cards mapping data to dynamic Lucide icons and colors based on health/status.
- [ ] **3.7:** Write custom hooks for fetching recent process instances.
- [ ] **3.8:** Build the Dashboard Activity Table to display recent runs.

## Sprint 4: Process Definitions & Visualizer Setup
- [ ] **4.1:** Write custom hooks (`useDefinitions`, `useCreateDefinition`, `useDeleteDefinition`).
- [ ] **4.2:** Build the Definitions page with a data table and "Create New" context menu.
- [ ] **4.3:** Install `@monaco-editor/react`. Build a reusable `JsonEditor` component for editing definition rules.
- [ ] **4.4:** Build the "Start Flow" modal dialog (Definition selector, Version input, Monaco editor for `initialData`).
- [ ] **4.5:** Install `reactflow`. Write an adapter parser function to transform EDOE `transitions_json` rules (conditional, parallel, sub-process) into React Flow `Node[]` and `Edge[]`.
- [ ] **4.6:** Develop the `DefinitionVisualizer` component rendering the parsed node graph.

## Sprint 5: Process Monitoring & Detail View
- [ ] **5.1:** Write hooks (`useProcessInstances` with pagination and filter arguments).
- [ ] **5.2:** Build the Instances table supporting backend pagination, definition dropdown filtering, and status filtering.
- [ ] **5.3:** Build the Process Detail outer shell (`/instances/:id`) incorporating the Header Ribbon, dynamic Status Badges, and Parent/Child Navigation links. Add step duration calculations using `stepStartedAt`.
- [ ] **5.4:** Implement automatic data refetching/polling (e.g., `refetchInterval: 3000`) for instances still in `RUNNING`, `SCHEDULED`, or `WAITING_FOR_CHILD`.
- [ ] **5.5:** Embed the `DefinitionVisualizer` inside the Process Detail page. Enhance the adapter parser to apply specific styling (CSS classes/Tailwind) to the `current_step` node and failed nodes (including stripping `__MI__\d+` step suffixes for Multi-Instance support).

## Sprint 6: Audit Trail & Complex Interventions
- [ ] **6.1:** Write the `useAuditTrail` query hook.
- [ ] **6.2:** Develop an `AuditTimeline` custom component leveraging Tailwind CSS borders/circles to visualize state changes and `contextSnapshot` payloads beautifully.
- [ ] **6.3:** Build reusable JSON context viewer tab for real-time process state inspection.
- [ ] **6.4:** Implement intervention mutation hooks: `useCancelProcess`, `useRetryProcess`, `useAdvanceProcess`, `useWakeProcess`.
- [ ] **6.5:** Hook administrative mutation buttons in the Header Ribbon to their respective functions, including confirmation alerts.
- [ ] **6.6:** Build and integrate the "Signal Modal" form (Event Type input, Data JSON input) calling `/api/processes/{id}/signal`.
- [ ] **6.7:** Build the "Replay / Time-Travel Modal". Pull the audit trail, allow the user to select an eligible `PROCESS_STARTED` or `STEP_TRANSITION` event, and submit to `/api/processes/{id}/replay?fromStep=X`.

## Sprint 7: Human Task Inbox
- [ ] **7.1:** Add `HumanTaskResponse`, `HumanTaskDefinition`, `HumanTaskStatus`, and `CompleteTaskRequest` to `types/api.ts`.
- [ ] **7.2:** Write custom React Query hooks:
  - `useHumanTasks(status?, assignee?)` — queries `GET /api/tasks` with optional filters, `refetchInterval: 10000` for live updates.
  - `useHumanTask(id)` — queries `GET /api/tasks/{id}`.
  - `useCompleteTask()` — mutation for `POST /api/tasks/{id}/complete`; invalidates `useHumanTasks` on success.
  - `useCancelTask()` — mutation for `POST /api/tasks/{id}/cancel`.
- [ ] **7.3:** Build the Task Inbox page (`/tasks`):
  - Left panel: filterable task list showing `taskName`, `processDefinitionName`, `assignee`, `createdAt`, and a `PENDING` badge.
  - Right panel: when a task is selected, render the dynamic form from `formSchema.fields` and a "Complete" button.
  - Add `?status=` and `?assignee=` filter controls in the command bar.
- [ ] **7.4:** Build the `DynamicTaskForm` component:
  - Reads `formSchema.fields` and renders the appropriate input per `type`: `boolean` → checkbox, `string` → text input, `number` → number input, `select` → dropdown (using `options` from the field schema if present).
  - On submit, collects all field values into a `resultData` object and calls `useCompleteTask`.
  - Shows a success toast on completion and reloads the task list.
- [ ] **7.5:** Add sidebar navigation link for `/tasks` with an unread-count badge driven by `useHumanTasks({ status: "PENDING" }).data?.length`.
- [ ] **7.6:** On the Process Detail page (`/instances/:id`):
  - When `status === "SUSPENDED"`, add a "View Task" link that queries `GET /api/tasks?status=PENDING` and filters by `processInstanceId`, then navigates to `/tasks` with the task pre-selected.
  - In the audit trail, render `HUMAN_TASK_CREATED`, `HUMAN_TASK_COMPLETED`, and `HUMAN_TASK_CANCELLED` events with a `UserCheck` icon and the task name from the audit `payload`.
- [ ] **7.7:** Add MSW handlers for all four `/api/tasks` routes using the mock data from section 10.5.

## Sprint 8: Webhooks & Final Polish
- [ ] **8.1:** Write Webhook CRUD and Toggle query/mutation hooks.
- [ ] **8.2:** Build the Webhooks management table displaying active states and subscribed events.
- [ ] **8.3:** Build the Webhook creation Slide-out Panel (URL input, Event Multi-select using a custom combobox or checkboxes, Secret input).
- [ ] **8.4:** Implement Empty States and Loading Skeletons (`shadcn/ui` Skeleton) across all tables and dashboard widgets.
- [ ] **8.5:** Audit responsive layouts to ensure the frontend works effectively on smaller laptop screens or tablets.
- [ ] **8.6:** Final QA, console warning cleanups, and deploy configuration settings (e.g., multi-environment `env` support).
