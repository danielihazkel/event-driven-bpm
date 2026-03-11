package com.edoe.orchestrator.dto;

import java.util.Map;

/**
 * Defines a human task gate embedded inline in a {@link TransitionRule}.
 * When the engine evaluates a rule containing this object, it suspends the
 * process at {@code signalEvent} and creates a {@code HumanTask} DB record
 * so the frontend can discover, render, and submit the task.
 */
public record HumanTaskDefinition(
        /** Display name shown in the task list UI. */
        String taskName,

        /** Signal event key — must match the key in the process transitions map. */
        String signalEvent,

        /**
         * Dynamic form specification. The engine stores this as JSON; the frontend
         * uses it to render the form fields. Example:
         * {@code {"fields":[{"name":"approved","type":"boolean","label":"Approve?"}]}}
         */
        Map<String, Object> formSchema,

        /**
         * Optional assignee filter. Literal string or SpEL expression starting with
         * {@code #} (evaluated against process context). Null means unassigned.
         */
        String assignee) {
}
