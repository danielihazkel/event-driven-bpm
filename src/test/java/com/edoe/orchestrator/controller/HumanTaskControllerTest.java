package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.config.SecurityConfig;
import com.edoe.orchestrator.dto.CompleteTaskRequest;
import com.edoe.orchestrator.dto.HumanTaskResponse;
import com.edoe.orchestrator.entity.HumanTask;
import com.edoe.orchestrator.entity.HumanTaskStatus;
import com.edoe.orchestrator.service.HumanTaskService;
import com.edoe.orchestrator.service.ManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ HumanTaskController.class, GlobalExceptionHandler.class })
@Import(SecurityConfig.class)
@TestPropertySource(properties = "edoe.orchestrator.jwt.secret=dGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS0tLS0tLS0tLS0=")
class HumanTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HumanTaskService humanTaskService;

    @MockBean
    private ManagementService managementService;

    private HumanTaskResponse sampleResponse(UUID id, UUID processId, HumanTaskStatus status) {
        return new HumanTaskResponse(id, processId, "LOAN_APPROVAL", "Manual Review",
                "APPROVAL_GRANTED",
                Map.of("fields", List.of()),
                null, status,
                LocalDateTime.now(), null, null);
    }

    private HumanTask sampleTask(UUID id, UUID processId) throws Exception {
        HumanTask task = new HumanTask(processId, "LOAN_APPROVAL", "Manual Review",
                "APPROVAL_GRANTED", "{\"fields\":[]}", null);
        Field f = HumanTask.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(task, id);
        return task;
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void listTasks_returnsAllPending() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        when(humanTaskService.listTasks(HumanTaskStatus.PENDING, null))
                .thenReturn(List.of(sampleResponse(taskId, processId, HumanTaskStatus.PENDING)));

        mockMvc.perform(get("/api/tasks").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskName").value("Manual Review"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getTask_returns404WhenNotFound() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(humanTaskService.getTask(taskId))
                .thenThrow(new NoSuchElementException("Human task not found: " + taskId));

        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Human task not found")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void completeTask_signalsProcess() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        HumanTask task = sampleTask(taskId, processId);

        when(humanTaskService.completeTask(eq(taskId), any())).thenReturn(task);
        when(humanTaskService.getTask(taskId))
                .thenReturn(sampleResponse(taskId, processId, HumanTaskStatus.COMPLETED));

        CompleteTaskRequest req = new CompleteTaskRequest(Map.of("approved", true));

        mockMvc.perform(post("/api/tasks/" + taskId + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(managementService).signalProcess(eq(processId), eq("APPROVAL_GRANTED"), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cancelTask_marksTaskCancelled() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        when(humanTaskService.cancelTask(taskId))
                .thenReturn(sampleResponse(taskId, processId, HumanTaskStatus.CANCELLED));

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
