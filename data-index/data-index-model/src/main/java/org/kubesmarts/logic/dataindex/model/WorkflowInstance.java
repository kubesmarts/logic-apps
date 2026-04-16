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
package org.kubesmarts.logic.dataindex.model;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import org.eclipse.microprofile.graphql.Ignore;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Workflow instance execution.
 *
 * <p><b>Design principle:</b> Contains ONLY fields from Quarkus Flow structured logging events.
 * All fields map directly to runtime event data.
 *
 * <p><b>Event sources:</b>
 * <ul>
 *   <li>workflow.instance.started → id, namespace, name, version, status, start, input
 *   <li>workflow.instance.completed → status, end, output
 *   <li>workflow.instance.faulted → status, end, error
 *   <li>workflow.instance.status.changed → status, lastUpdate
 *   <li>workflow.task.* events → taskExecutions (aggregated)
 * </ul>
 *
 * <p>Data Index v1.0.0 is read-only - instances are created by Quarkus Flow
 * runtime and ingested via FluentBit → PostgreSQL.
 */
public class WorkflowInstance {

    /**
     * Workflow instance ID.
     * <p>Source: instanceId from Quarkus Flow events
     */
    private String id;

    /**
     * Workflow namespace.
     * <p>Source: workflowNamespace from Quarkus Flow events
     */
    private String namespace;

    /**
     * Workflow name.
     * <p>Source: workflowName from Quarkus Flow events
     */
    private String name;

    /**
     * Workflow version.
     * <p>Source: workflowVersion from Quarkus Flow events
     */
    private String version;

    /**
     * Workflow instance status.
     * <p>Source: status from Quarkus Flow events
     * <p>Values: RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED
     */
    private WorkflowInstanceStatus status;

    /**
     * Instance start time.
     * <p>Source: startTime from workflow.instance.started event
     */
    @JsonProperty("startDate")
    private ZonedDateTime start;

    /**
     * Instance end time.
     * <p>Source: endTime from workflow.instance.completed or workflow.instance.faulted events
     */
    @JsonProperty("endDate")
    private ZonedDateTime end;

    /**
     * Last update timestamp.
     * <p>Source: lastUpdateTime from workflow.instance.status.changed event
     */
    private ZonedDateTime lastUpdate;

    /**
     * Workflow input data.
     * <p>Source: input from workflow.instance.started event
     * <p>TODO: Implement JSON scalar mapping for GraphQL
     */
    @Ignore
    private JsonNode input;

    /**
     * Workflow output data.
     * <p>Source: output from workflow.instance.completed event
     * <p>TODO: Implement JSON scalar mapping for GraphQL
     */
    @Ignore
    private JsonNode output;

    /**
     * Task executions for this instance.
     * <p>Source: Aggregated from workflow.task.* events
     */
    @JsonProperty("taskExecutions")
    private List<TaskExecution> taskExecutions;

    /**
     * Error information if instance failed.
     * <p>Source: error object from workflow.instance.faulted event
     */
    private WorkflowInstanceError error;

    /**
     * Reference to workflow definition.
     * <p>TBD: Will be populated when workflow definitions are available
     */
    @Ignore
    private Workflow workflow;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        if (id != null && !id.trim().isEmpty()) {
            this.id = id;
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public WorkflowInstanceStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowInstanceStatus status) {
        this.status = status;
    }

    public ZonedDateTime getStart() {
        return start;
    }

    public void setStart(ZonedDateTime start) {
        this.start = start;
    }

    public ZonedDateTime getEnd() {
        return end;
    }

    public void setEnd(ZonedDateTime end) {
        this.end = end;
    }

    public ZonedDateTime getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(ZonedDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public JsonNode getInput() {
        return input;
    }

    public void setInput(JsonNode input) {
        this.input = input;
    }

    public JsonNode getOutput() {
        return output;
    }

    public void setOutput(JsonNode output) {
        this.output = output;
    }

    public List<TaskExecution> getTaskExecutions() {
        return taskExecutions;
    }

    public void setTaskExecutions(List<TaskExecution> taskExecutions) {
        this.taskExecutions = taskExecutions;
    }

    public WorkflowInstanceError getError() {
        return error;
    }

    public void setError(WorkflowInstanceError error) {
        this.error = error;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Workflow workflow) {
        this.workflow = workflow;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WorkflowInstance that = (WorkflowInstance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkflowInstance{" +
                "id='" + id + '\'' +
                ", namespace='" + namespace + '\'' +
                ", name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", status=" + status +
                ", start=" + start +
                ", end=" + end +
                ", lastUpdate=" + lastUpdate +
                ", input=" + input +
                ", output=" + output +
                ", taskExecutions=" + taskExecutions +
                ", error=" + error +
                ", workflow=" + workflow +
                '}';
    }
}
