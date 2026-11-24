package org.kubesmarts.logic.dataindex.ingestion.kafka.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.serverlessworkflow.impl.LifecycleEvents;
import io.serverlessworkflow.impl.WorkflowError;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCancelledCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCompletedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCompletedCEDataWithOutput;
import io.serverlessworkflow.impl.lifecycle.ce.TaskFailedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskStartedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskStartedCEDataWithInput;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCancelledCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCompletedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCompletedCEDataWithOutput;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowFailedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowStartedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowStartedCEDataWithInput;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowStatusCEDataEvent;
import org.kubesmarts.logic.dataindex.ingestion.persistence.TaskExecution;
import org.kubesmarts.logic.dataindex.ingestion.persistence.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.Error;
import org.kubesmarts.logic.dataindex.model.LifecycleEventUtils;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import java.time.OffsetDateTime;

public final class Mapper {

    private Mapper() {}

    public static WorkflowInstance mapWorkflowInstanceEvent(CloudEvent cloudEvent, WorkflowCEData data, ObjectMapper objectMapper) throws JsonProcessingException {

        String id = data.getName();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("WorkflowCEData name field is null or empty.");
        }

        String status = defineStatus(cloudEvent, data);

        OffsetDateTime startedAt = null;
        OffsetDateTime endedAt = null;
        OffsetDateTime lastUpdate = null;
        Error error = null;
        if (data instanceof WorkflowStartedCEData started) {
            startedAt = started.getStartedAt();
        } else if (data instanceof WorkflowCompletedCEData completed) {
            endedAt = completed.getCompletedAt();
        } else if (data instanceof WorkflowFailedCEData failed) {
            endedAt = failed.getFaultedAt();
            if (failed.getError() != null) {
                error = mapError(failed.getError());
            }
        } else if (data instanceof WorkflowCancelledCEData cancelled) {
            endedAt = cancelled.getCancelledAt();
        } else if (data instanceof WorkflowStatusCEDataEvent statusChanged) {
            lastUpdate = statusChanged.getUpdatedAt();
        }

        String input = null;
        String output = null;
        if (data instanceof WorkflowStartedCEDataWithInput withInput && withInput.getInput() != null) {
            input = objectMapper.writeValueAsString(withInput.getInput());
        }
        if (data instanceof WorkflowCompletedCEDataWithOutput withOutput && withOutput.getOutput() != null) {
            output = objectMapper.writeValueAsString(withOutput.getOutput());
        }

        return new WorkflowInstance(
                id,
                data.getDefinition().namespace(),
                data.getDefinition().name(),
                data.getDefinition().version(),
                null, // TODO: https://github.com/kubesmarts/logic-apps/issues/79
                WorkflowInstanceStatus.valueOf(status),
                startedAt,
                endedAt,
                lastUpdate,
                input,
                output,
                error,
                cloudEvent.getTime());
    }

    public static TaskExecution mapTaskExecutionEvent(CloudEvent cloudEvent, TaskCEData data, ObjectMapper jackson) throws JsonProcessingException {

        String instanceId = data.getWorkflow();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("The workflow's instance id field ('workflow') is null or empty.");
        }

        String taskPosition = data.getTask();
        if (taskPosition == null || taskPosition.isBlank()) {
            throw new IllegalArgumentException("The task position field ('task') is null or empty.");
        }

        String status = defineStatus(cloudEvent, data);

        OffsetDateTime startedAt = null;
        if (data instanceof TaskStartedCEData started) {
            startedAt = started.getStartedAt();
        }

        OffsetDateTime endedAt = null;
        Error error = null;
        if (data instanceof TaskCompletedCEData completed) {
            endedAt = completed.getCompletedAt();
        } else if (data instanceof TaskFailedCEData failed) {
            endedAt = failed.getFaultedAt();
            if (failed.getError() != null) {
                error = mapError(failed.getError());
            }
        } else if (data instanceof TaskCancelledCEData cancelled) {
            endedAt = cancelled.getCancelledAt();
        }

        String input = null;
        String output = null;
        if (data instanceof TaskStartedCEDataWithInput withInput && withInput.getInput() != null) {
            input = jackson.writeValueAsString(withInput.getInput());
        }
        if (data instanceof TaskCompletedCEDataWithOutput withOutput && withOutput.getOutput() != null) {
            output = jackson.writeValueAsString(withOutput.getOutput());
        }

        return new TaskExecution(
                instanceId,
                taskPosition.substring(taskPosition.lastIndexOf("/") + 1),
                taskPosition,
                status,
                startedAt,
                endedAt,
                input,
                output,
                error,
                cloudEvent.getTime());
    }

    private static String defineStatus(CloudEvent cloudEvent, Object data) {
        if (cloudEvent.getType().equals(LifecycleEvents.WORKFLOW_STATUS_CHANGED)
                && data instanceof WorkflowStatusCEDataEvent statusChanged) {
            return statusChanged.getStatus();
        }
        String status = LifecycleEventUtils.defineStatusLooking(cloudEvent.getType());
        if (status == null) {
            throw new IllegalArgumentException("It was not possible to define status looking for 'status' event.");
        }
        return status;
    }

    private static Error mapError(WorkflowError workflowError) {
        Error error = new Error();
        error.setType(workflowError.type());
        error.setTitle(workflowError.title());
        error.setDetail(workflowError.detail());
        error.setStatus(workflowError.status());
        error.setInstance(workflowError.instance());
        return error;
    }
}
