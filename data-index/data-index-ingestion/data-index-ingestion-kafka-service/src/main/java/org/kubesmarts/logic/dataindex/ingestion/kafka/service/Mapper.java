package org.kubesmarts.logic.dataindex.ingestion.kafka.service;

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
import org.kubesmarts.logic.dataindex.model.Error;
import org.kubesmarts.logic.dataindex.model.LifecycleEventUtils;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;
public final class Mapper {

    private Mapper() {}

    public static WorkflowInstance mapWorkflowInstanceEvent(CloudEvent cloudEvent, WorkflowCEData data, ObjectMapper jackson) {

        String id = data.getName();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("WorkflowCEData name field is null or empty.");
        }

        String status = defineStatus(cloudEvent, data);

        WorkflowInstance workflow = new WorkflowInstance();
        workflow.setId(id);
        workflow.setNamespace(data.getDefinition().namespace());
        workflow.setName(data.getDefinition().name());
        workflow.setVersion(data.getDefinition().version());
        workflow.setStatus(WorkflowInstanceStatus.valueOf(status));
        workflow.setEventTimestamp(cloudEvent.getTime().toZonedDateTime());

        if (data instanceof WorkflowStartedCEData started) {
            workflow.setStart(started.getStartedAt().toZonedDateTime());
        } else if (data instanceof WorkflowCompletedCEData completed) {
            workflow.setEnd(completed.getCompletedAt().toZonedDateTime());
        } else if (data instanceof WorkflowFailedCEData failed) {
            workflow.setEnd(failed.getFaultedAt().toZonedDateTime());
            if (failed.getError() != null) {
                workflow.setError(mapError(failed.getError()));
            }
        } else if (data instanceof WorkflowCancelledCEData cancelled) {
            workflow.setEnd(cancelled.getCancelledAt().toZonedDateTime());
        } else if (data instanceof WorkflowStatusCEDataEvent statusChanged) {
            workflow.setLastUpdate(statusChanged.getUpdatedAt().toZonedDateTime());
        }

        if (data instanceof WorkflowStartedCEDataWithInput withInput && withInput.getInput() != null) {
            workflow.setInput(jackson.valueToTree(withInput.getInput()));
        }
        if (data instanceof WorkflowCompletedCEDataWithOutput withOutput && withOutput.getOutput() != null) {
            workflow.setOutput(jackson.valueToTree(withOutput.getOutput()));
        }

        return workflow;
    }

    public static TaskExecution mapTaskExecutionEvent(CloudEvent cloudEvent, TaskCEData data, ObjectMapper jackson) {

        String instanceId = data.getWorkflow();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("The workflow's instance id field ('workflow') is null or empty.");
        }

        String taskPosition = data.getTask();
        if (taskPosition == null || taskPosition.isBlank()) {
            throw new IllegalArgumentException("The task position field ('task') is null or empty.");
        }

        String status = defineStatus(cloudEvent, data);

        TaskExecution taskExecution = new TaskExecution();
        taskExecution.setInstanceId(instanceId);
        taskExecution.setEventTimestamp(cloudEvent.getTime().toZonedDateTime());
        taskExecution.setStatus(status);
        taskExecution.setTaskPosition(taskPosition);
        taskExecution.setTaskName(taskPosition.substring(taskPosition.lastIndexOf("/") + 1));

        if (data instanceof TaskStartedCEData started) {
            taskExecution.setStart(started.getStartedAt().toZonedDateTime());
        }

        if (data instanceof TaskCompletedCEData completed) {
            taskExecution.setEnd(completed.getCompletedAt().toZonedDateTime());
        } else if (data instanceof TaskFailedCEData failed) {
            taskExecution.setEnd(failed.getFaultedAt().toZonedDateTime());
            if (failed.getError() != null) {
                taskExecution.setError(mapError(failed.getError()));
            }
        } else if (data instanceof TaskCancelledCEData cancelled) {
            taskExecution.setEnd(cancelled.getCancelledAt().toZonedDateTime());
        }

        if (data instanceof TaskStartedCEDataWithInput withInput && withInput.getInput() != null) {
            taskExecution.setInput(jackson.valueToTree(withInput.getInput()));
        }
        if (data instanceof TaskCompletedCEDataWithOutput withOutput && withOutput.getOutput() != null) {
            taskExecution.setOutput(jackson.valueToTree(withOutput.getOutput()));
        }

        return taskExecution;
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
