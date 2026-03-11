package com.edoe.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Configuration for a native HTTP step embedded in a {@link TransitionRule}.
 *
 * <p>The {@code url}, {@code body}, and individual header <em>values</em> support
 * Spring Expression Language (SpEL). Any string that contains {@code #} or {@code T(}
 * is evaluated as a SpEL expression against the current process {@code context_data};
 * all other strings are used verbatim as literals.</p>
 *
 * <p>Example — SpEL in url and body:</p>
 * <pre>
 * {
 *   "url":    "'https://api.example.com/users/' + #userId",
 *   "method": "POST",
 *   "headers": { "Authorization": "'Bearer ' + #apiToken" },
 *   "body":   "'{\"userId\":\"' + #userId + '\"}'"
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "HTTP call configuration for a native HTTP step. SpEL is supported in url, header values, and body.")
public record HttpRequestConfig(

        @Schema(description = "Target URL. Supports SpEL (use #varName syntax); plain URLs are used verbatim.",
                example = "'https://api.example.com/users/' + #userId")
        String url,

        @Schema(description = "HTTP method (GET, POST, PUT, PATCH, DELETE).", example = "GET")
        String method,

        @Schema(description = "Request headers. Values support SpEL.",
                example = "{\"Authorization\": \"'Bearer ' + #token\"}")
        Map<String, String> headers,

        @Schema(description = "Request body as a string. Supports SpEL. Omit for GET requests.",
                example = "'{\"orderId\":\"' + #orderId + '\"}'")
        String body) {
}
