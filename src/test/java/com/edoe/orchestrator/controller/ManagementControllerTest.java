package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.config.SecurityConfig;
import com.edoe.orchestrator.dto.*;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.service.ManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.edoe.orchestrator.dto.TransitionRule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ ManagementController.class, GlobalExceptionHandler.class })
@Import(SecurityConfig.class)
@TestPropertySource(properties = "edoe.orchestrator.jwt.secret=dGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS0tLS0tLS0tLS0=")
class ManagementControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockBean
        private ManagementService managementService;

        private ProcessDefinitionResponse sampleDefinition(String name) {
                return new ProcessDefinitionResponse(1L, name, 1, "STEP_1",
                                Map.of("STEP_1_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))),
                                Map.of(),
                                LocalDateTime.now(), LocalDateTime.now());
        }

        private ProcessInstanceResponse sampleInstance(UUID id, ProcessStatus status) {
                return new ProcessInstanceResponse(id, "FLOW", 1, "STEP_1", status,
                                LocalDateTime.now(), LocalDateTime.now(), null, "{}", null);
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void listDefinitions_returns200() throws Exception {
                when(managementService.listDefinitions()).thenReturn(List.of(sampleDefinition("FLOW_A")));

                mockMvc.perform(get("/api/definitions"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].name").value("FLOW_A"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createDefinition_returns201() throws Exception {
                ProcessDefinitionRequest req = new ProcessDefinitionRequest("NEW_FLOW", "STEP_1",
                                Map.of("STEP_1_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))), Map.of());
                when(managementService.createDefinition(any())).thenReturn(sampleDefinition("NEW_FLOW"));

                mockMvc.perform(post("/api/definitions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.name").value("NEW_FLOW"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void createDefinition_returns409WhenConflict() throws Exception {
                ProcessDefinitionRequest req = new ProcessDefinitionRequest("EXISTING", "STEP_1", Map.of(), Map.of());
                when(managementService.createDefinition(any()))
                                .thenThrow(new IllegalStateException("Definition already exists: EXISTING"));

                mockMvc.perform(post("/api/definitions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.error").value("Definition already exists: EXISTING"));
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void getDefinition_returns200WhenFound() throws Exception {
                when(managementService.getDefinition("MY_FLOW")).thenReturn(sampleDefinition("MY_FLOW"));

                mockMvc.perform(get("/api/definitions/MY_FLOW"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.name").value("MY_FLOW"));
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void getDefinition_returns404WhenMissing() throws Exception {
                when(managementService.getDefinition("MISSING"))
                                .thenThrow(new NoSuchElementException("Definition not found: MISSING"));

                mockMvc.perform(get("/api/definitions/MISSING"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error").value("Definition not found: MISSING"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void deleteDefinition_returns409WhenActiveProcesses() throws Exception {
                doThrow(new IllegalStateException("Cannot delete definition with active processes: BUSY"))
                                .when(managementService).deleteDefinition("BUSY");

                mockMvc.perform(delete("/api/definitions/BUSY"))
                                .andExpect(status().isConflict());
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void listProcesses_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                PageImpl<ProcessInstanceResponse> page = new PageImpl<>(
                                List.of(sampleInstance(id, ProcessStatus.RUNNING)));
                when(managementService.listProcesses(any(), any(), any())).thenReturn(page);

                mockMvc.perform(get("/api/processes"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.content[0].status").value("RUNNING"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void cancelProcess_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                ProcessInstanceResponse cancelled = new ProcessInstanceResponse(id, "FLOW", 1, "STEP_1",
                                ProcessStatus.CANCELLED, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                                "{}", null);
                when(managementService.cancelProcess(id)).thenReturn(cancelled);

                mockMvc.perform(post("/api/processes/{id}/cancel", id))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void advanceProcess_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.advanceProcess(id)).thenReturn(sampleInstance(id, ProcessStatus.RUNNING));

                mockMvc.perform(post("/api/processes/{id}/advance", id))
                                .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void getMetricsSummary_returns200() throws Exception {
                MetricsSummaryResponse metrics = new MetricsSummaryResponse(17, 3, 10, 2, 1, 1, 0, 0, 0.77, 0);
                when(managementService.getMetricsSummary()).thenReturn(metrics);

                mockMvc.perform(get("/api/metrics/summary"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.total").value(17))
                                .andExpect(jsonPath("$.running").value(3))
                                .andExpect(jsonPath("$.completed").value(10));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void signalProcess_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                ProcessInstanceResponse resumed = new ProcessInstanceResponse(id, "LOAN_APPROVAL", 1, "DISBURSE_FUNDS",
                                ProcessStatus.RUNNING, LocalDateTime.now(), LocalDateTime.now(), null, "{}", null);
                when(managementService.signalProcess(eq(id), eq("APPROVAL_GRANTED"), any()))
                                .thenReturn(resumed);

                SignalRequest req = new SignalRequest("APPROVAL_GRANTED", Map.of("approved", true));
                mockMvc.perform(post("/api/processes/{id}/signal", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.currentStep").value("DISBURSE_FUNDS"))
                                .andExpect(jsonPath("$.status").value("RUNNING"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void signalProcess_returns404WhenNotFound() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.signalProcess(eq(id), any(), any()))
                                .thenThrow(new NoSuchElementException("Process not found: " + id));

                SignalRequest req = new SignalRequest("APPROVAL_GRANTED", Map.of());
                mockMvc.perform(post("/api/processes/{id}/signal", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void signalProcess_returns409WhenNotSuspended() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.signalProcess(eq(id), any(), any()))
                                .thenThrow(new IllegalStateException(
                                                "Cannot signal process " + id + " in status: RUNNING"));

                SignalRequest req = new SignalRequest("APPROVAL_GRANTED", Map.of());
                mockMvc.perform(post("/api/processes/{id}/signal", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void wakeProcess_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.wakeProcess(id)).thenReturn(sampleInstance(id, ProcessStatus.RUNNING));

                mockMvc.perform(post("/api/processes/{id}/wake", id))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("RUNNING"));
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void getAuditTrail_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                AuditLogResponse entry = new AuditLogResponse(
                                UUID.randomUUID(), id,
                                com.edoe.orchestrator.entity.AuditEventType.PROCESS_STARTED,
                                "STEP_1", null, "RUNNING", null,
                                java.time.LocalDateTime.now());
                when(managementService.getAuditTrail(id)).thenReturn(List.of(entry));

                mockMvc.perform(get("/api/processes/{id}/audit", id))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].eventType").value("PROCESS_STARTED"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void acknowledgeCompensationFailure_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                ProcessInstanceResponse cancelled = new ProcessInstanceResponse(id, "FLOW", 1, "COMPENSATE_PAYMENT",
                                ProcessStatus.CANCELLED, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                                "{}", null);
                when(managementService.acknowledgeCompensationFailure(id)).thenReturn(cancelled);

                mockMvc.perform(post("/api/processes/{id}/acknowledge-compensation-failure", id))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void acknowledgeCompensationFailure_returns409WhenNotInCompensationFailed() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.acknowledgeCompensationFailure(id))
                                .thenThrow(new IllegalStateException("Cannot acknowledge process not in COMPENSATION_FAILED status: RUNNING"));

                mockMvc.perform(post("/api/processes/{id}/acknowledge-compensation-failure", id))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void replayProcess_returns200() throws Exception {
                UUID id = UUID.randomUUID();
                ProcessInstanceResponse running = new ProcessInstanceResponse(id, "FLOW", 1, "STEP_1",
                                ProcessStatus.RUNNING, LocalDateTime.now(), LocalDateTime.now(), null, "{}", null);
                when(managementService.replayFromStep(eq(id), eq("STEP_1"))).thenReturn(running);

                mockMvc.perform(post("/api/processes/{id}/replay", id).param("fromStep", "STEP_1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("RUNNING"));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void replayProcess_stepNotFound_returns404() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.replayFromStep(eq(id), eq("MISSING")))
                                .thenThrow(new NoSuchElementException("Step 'MISSING' not found in audit trail for process " + id));

                mockMvc.perform(post("/api/processes/{id}/replay", id).param("fromStep", "MISSING"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void getAuditTrail_returns404WhenProcessMissing() throws Exception {
                UUID id = UUID.randomUUID();
                when(managementService.getAuditTrail(id))
                                .thenThrow(new NoSuchElementException("Process not found: " + id));

                mockMvc.perform(get("/api/processes/{id}/audit", id))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @WithMockUser(roles = "VIEWER")
        void viewerCannotPost_returns403() throws Exception {
                ProcessDefinitionRequest req = new ProcessDefinitionRequest("NEW_FLOW", "STEP_1", Map.of(), Map.of());

                mockMvc.perform(post("/api/definitions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isForbidden());
        }

        @Test
        void unauthenticated_returns401() throws Exception {
                mockMvc.perform(get("/api/definitions"))
                                .andExpect(status().isUnauthorized());
        }
}
