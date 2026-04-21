/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceError;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses Quarkus Flow structured logging events from log file.
 *
 * <p>This class reads quarkus-flow-events.log and converts JSON events
 * into WorkflowInstance and TaskExecution domain models.
 *
 * <p>Event handling follows the Data Index ingestion pipeline:
 * <ul>
 *   <li>workflow.started → creates new WorkflowInstance
 *   <li>workflow.completed → updates status, end time, output
 *   <li>workflow.faulted → updates status, end time, error
 *   <li>task.started → creates new TaskExecution
 *   <li>task.completed → updates exit time, output
 *   <li>task.faulted → updates exit time, error
 * </ul>
 */
public class EventLogParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse all workflow instances from log file.
     *
     * @param logFilePath path to quarkus-flow-events.log
     * @return map of instanceId → WorkflowInstance
     */
    public Map<String, WorkflowInstance> parseWorkflowInstances(Path logFilePath) throws IOException {
        List<String> lines = Files.readAllLines(logFilePath);
        Map<String, WorkflowInstance> instances = new HashMap<>();
        Map<String, List<TaskExecution>> tasksByInstance = new HashMap<>();

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }

            JsonNode event = objectMapper.readTree(line);
            String eventType = event.get("eventType").asText();
            String instanceId = event.get("instanceId").asText();

            // Check for task events first (task events also contain "workflow" in the eventType)
            if (eventType.contains(".task.")) {
                List<TaskExecution> tasks = tasksByInstance.computeIfAbsent(instanceId, id -> new ArrayList<>());
                updateTaskExecution(tasks, event, eventType);
            } else if (eventType.contains(".workflow.")) {
                WorkflowInstance instance = instances.computeIfAbsent(instanceId, id -> {
                    WorkflowInstance wi = new WorkflowInstance();
                    wi.setId(id);
                    return wi;
                });
                updateWorkflowInstance(instance, event, eventType);
            }
        }

        // Attach task executions to workflow instances
        for (Map.Entry<String, List<TaskExecution>> entry : tasksByInstance.entrySet()) {
            WorkflowInstance instance = instances.get(entry.getKey());
            if (instance != null) {
                instance.setTaskExecutions(entry.getValue());
            }
        }

        return instances;
    }

    private void updateWorkflowInstance(WorkflowInstance instance, JsonNode event, String eventType) {
        if (eventType.contains("started")) {
            if (event.has("workflowNamespace")) {
                instance.setNamespace(event.get("workflowNamespace").asText());
            }
            if (event.has("workflowName")) {
                instance.setName(event.get("workflowName").asText());
            }
            if (event.has("workflowVersion")) {
                instance.setVersion(event.get("workflowVersion").asText());
            }
            if (event.has("status")) {
                instance.setStatus(WorkflowInstanceStatus.valueOf(event.get("status").asText()));
            }
            if (event.has("startTime")) {
                instance.setStart(ZonedDateTime.parse(event.get("startTime").asText()));
            }
            if (event.has("input")) {
                instance.setInput(event.get("input"));
            }
        } else if (eventType.contains("completed")) {
            if (event.has("status")) {
                instance.setStatus(WorkflowInstanceStatus.valueOf(event.get("status").asText()));
            }
            if (event.has("endTime")) {
                instance.setEnd(ZonedDateTime.parse(event.get("endTime").asText()));
            }
            if (event.has("output")) {
                instance.setOutput(event.get("output"));
            }
        } else if (eventType.contains("faulted")) {
            if (event.has("status")) {
                instance.setStatus(WorkflowInstanceStatus.valueOf(event.get("status").asText()));
            }
            if (event.has("endTime")) {
                instance.setEnd(ZonedDateTime.parse(event.get("endTime").asText()));
            }
            if (event.has("error")) {
                JsonNode errorNode = event.get("error");
                WorkflowInstanceError error = new WorkflowInstanceError();
                if (errorNode.has("title")) {
                    error.setTitle(errorNode.get("title").asText());
                }
                if (errorNode.has("type")) {
                    error.setType(errorNode.get("type").asText());
                }
                if (errorNode.has("detail")) {
                    error.setDetail(errorNode.get("detail").asText());
                }
                if (errorNode.has("status")) {
                    error.setStatus(errorNode.get("status").asInt());
                }
                if (errorNode.has("instance")) {
                    error.setInstance(errorNode.get("instance").asText());
                }
                instance.setError(error);
            }
        } else if (eventType.contains("status")) {
            // Handle status-changed events
            if (event.has("status")) {
                instance.setStatus(WorkflowInstanceStatus.valueOf(event.get("status").asText()));
            }
            if (event.has("lastUpdateTime")) {
                instance.setLastUpdate(ZonedDateTime.parse(event.get("lastUpdateTime").asText()));
            }
        }
    }

    private void updateTaskExecution(List<TaskExecution> tasks, JsonNode event, String eventType) {
        String taskExecutionId = event.get("taskExecutionId").asText();
        String taskPosition = event.has("taskPosition") ? event.get("taskPosition").asText() : null;

        // Find existing task by taskPosition (tasks are identified by position, not execution ID)
        TaskExecution task = null;
        if (taskPosition != null) {
            task = tasks.stream()
                    .filter(t -> taskPosition.equals(t.getTaskPosition()))
                    .findFirst()
                    .orElse(null);
        }

        // If not found by position, create new task
        if (task == null) {
            task = new TaskExecution();
            task.setId(taskExecutionId); // Use first seen ID (from started event)
            tasks.add(task);
        }

        if (eventType.contains("started")) {
            task.setTaskName(event.get("taskName").asText());
            task.setTaskPosition(event.get("taskPosition").asText());
            task.setEnter(ZonedDateTime.parse(event.get("startTime").asText()));

            if (event.has("input")) {
                task.setInputArgs(event.get("input"));
            }
        } else if (eventType.contains("completed")) {
            task.setExit(ZonedDateTime.parse(event.get("endTime").asText()));

            // Set position/name if not already set (shouldn't happen, but defensive)
            if (event.has("taskPosition") && task.getTaskPosition() == null) {
                task.setTaskPosition(event.get("taskPosition").asText());
            }
            if (event.has("taskName") && task.getTaskName() == null) {
                task.setTaskName(event.get("taskName").asText());
            }

            if (event.has("output")) {
                task.setOutputArgs(event.get("output"));
            }
        } else if (eventType.contains("faulted")) {
            task.setExit(ZonedDateTime.parse(event.get("endTime").asText()));

            if (event.has("error") && event.get("error").has("title")) {
                task.setErrorMessage(event.get("error").get("title").asText());
            }
        }
    }

    /**
     * Get all events for a specific workflow instance.
     */
    public List<JsonNode> getEventsForInstance(Path logFilePath, String instanceId) throws IOException {
        return Files.readAllLines(logFilePath).stream()
                .filter(line -> !line.trim().isEmpty())
                .map(line -> {
                    try {
                        return objectMapper.readTree(line);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(event -> event.get("instanceId").asText().equals(instanceId))
                .collect(Collectors.toList());
    }
}
