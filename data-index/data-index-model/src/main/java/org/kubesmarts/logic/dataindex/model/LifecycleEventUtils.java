package org.kubesmarts.logic.dataindex.model;

import io.cloudevents.CloudEvent;
import io.serverlessworkflow.impl.LifecycleEvents;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCancelledCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskFailedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskResumedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskRetriedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCompletedCEDataWithOutput;
import io.serverlessworkflow.impl.lifecycle.ce.TaskStartedCEDataWithInput;
import io.serverlessworkflow.impl.lifecycle.ce.TaskSuspendedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCancelledCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCompletedCEDataWithOutput;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowFailedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowResumedCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowStartedCEDataWithInput;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowStatusCEDataEvent;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowSuspendedCEData;

import java.util.HashMap;
import java.util.Map;

public final class LifecycleEventUtils {

    private LifecycleEventUtils() {
    }

    private static final Map<String, Class> EVENTS = new HashMap<>();

    static {
        EVENTS.put(LifecycleEvents.WORKFLOW_STARTED, WorkflowStartedCEDataWithInput.class);
        EVENTS.put(LifecycleEvents.WORKFLOW_RESUMED, WorkflowResumedCEData.class);
        EVENTS.put(LifecycleEvents.WORKFLOW_SUSPENDED, WorkflowSuspendedCEData.class);
        EVENTS.put(LifecycleEvents.WORKFLOW_CANCELLED, WorkflowCancelledCEData.class);
        EVENTS.put(LifecycleEvents.WORKFLOW_COMPLETED, WorkflowCompletedCEDataWithOutput.class);
        EVENTS.put(LifecycleEvents.WORKFLOW_FAULTED, WorkflowFailedCEData.class);
        EVENTS.put(LifecycleEvents.WORKFLOW_STATUS_CHANGED, WorkflowStatusCEDataEvent.class);
        EVENTS.put(LifecycleEvents.TASK_STARTED, TaskStartedCEDataWithInput.class);
        EVENTS.put(LifecycleEvents.TASK_CANCELLED, TaskCancelledCEData.class);
        EVENTS.put(LifecycleEvents.TASK_COMPLETED, TaskCompletedCEDataWithOutput.class);
        EVENTS.put(LifecycleEvents.TASK_RESUMED, TaskResumedCEData.class);
        EVENTS.put(LifecycleEvents.TASK_SUSPENDED, TaskSuspendedCEData.class);
        EVENTS.put(LifecycleEvents.TASK_FAULTED, TaskFailedCEData.class);
        EVENTS.put(LifecycleEvents.TASK_RETRIED, TaskRetriedCEData.class);
    }


    /**
     * Define event or workflow status based on {@link CloudEvent#getType()}.
     * <p>
     * The {@link LifecycleEvents#WORKFLOW_STATUS_CHANGED} is ignored and return null.
     */
    public static String defineStatusLooking(String eventType) {
        return switch (eventType) {
            case LifecycleEvents.TASK_RESUMED,
                 LifecycleEvents.TASK_STARTED,
                 // workflow
                 LifecycleEvents.WORKFLOW_RESUMED,
                 LifecycleEvents.WORKFLOW_STARTED -> WorkflowStatus.RUNNING.name();
            case LifecycleEvents.TASK_SUSPENDED,
                 LifecycleEvents.WORKFLOW_SUSPENDED -> WorkflowStatus.SUSPENDED.name();
            case LifecycleEvents.TASK_CANCELLED,
                 LifecycleEvents.WORKFLOW_CANCELLED -> WorkflowStatus.CANCELLED.name();
            case LifecycleEvents.TASK_FAULTED -> "FAILED"; // for task faulted should be FAILED
            case LifecycleEvents.WORKFLOW_FAULTED -> WorkflowStatus.FAULTED.name();
            case LifecycleEvents.TASK_COMPLETED,
                 LifecycleEvents.WORKFLOW_COMPLETED -> WorkflowStatus.COMPLETED.name();
            // "status-changed" is not handled because it points to the new status in the event payload
            default -> null;
        };
    }

    public static Class<?> getEventClass(String type) {
        if (EVENTS.get(type) != null) {
            return EVENTS.get(type);
        }
        throw new IllegalArgumentException(type + " is not a valid lifecycle event type");
    }
}
