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
package org.kubesmarts.logic.dataindex.storage.entity;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * JPA entity for workflow instance executions.
 *
 * <p><b>Design principle:</b> This entity is designed to store data from Quarkus Flow
 * structured logging events. Every field maps directly to data emitted in workflow lifecycle events.
 *
 * <p><b>Event sources:</b>
 * <ul>
 *   <li>workflow.instance.started → id, namespace, name, version, status, start, variables(input)
 *   <li>workflow.instance.completed → status, end, variables(output)
 *   <li>workflow.instance.faulted → status, end, error
 *   <li>workflow.instance.status.changed → status, lastUpdate
 * </ul>
 *
 * <p>Maps to WorkflowInstance domain model.
 */
@Entity
@Table(name = "workflow_instances")
public class WorkflowInstanceEntity extends AbstractEntity {

    /**
     * Workflow instance ID.
     * <p>Source: instanceId from Quarkus Flow events
     */
    @Id
    private String id;

    /**
     * Workflow namespace (SW 1.0.0).
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
    @Enumerated(EnumType.STRING)
    private WorkflowInstanceStatus status;

    /**
     * Instance start time.
     * <p>Source: startTime from workflow.instance.started event
     */
    @Column(name = "\"start\"")
    private ZonedDateTime start;

    /**
     * Instance end time.
     * <p>Source: endTime from workflow.instance.completed or workflow.instance.faulted events
     */
    @Column(name = "\"end\"")
    private ZonedDateTime end;

    /**
     * Last update timestamp.
     * <p>Source: lastUpdateTime from workflow.instance.status.changed event
     */
    private ZonedDateTime lastUpdate;

    /**
     * Workflow input data.
     * <p>Source: input from workflow.instance.started event
     * <p>Stored as JSONB in PostgreSQL
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;

    /**
     * Workflow output data.
     * <p>Source: output from workflow.instance.completed event
     * <p>Stored as JSONB in PostgreSQL
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;

    /**
     * Task executions for this instance.
     * <p>Source: workflow.task.* events aggregated into TaskInstanceEntity records
     */
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "workflowInstance")
    private List<TaskInstanceEntity> taskExecutions;

    /**
     * Error information if instance failed.
     * <p>Source: error object from workflow.instance.faulted event
     */
    @Embedded
    private ErrorEntity error;

    /**
     * Record creation timestamp.
     * <p>Auto-populated by database trigger when row is inserted
     */
    private ZonedDateTime createdAt;

    /**
     * Record last update timestamp.
     * <p>Auto-populated by database trigger when row is updated
     */
    private ZonedDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public List<TaskInstanceEntity> getTaskExecutions() {
        return taskExecutions;
    }

    public void setTaskExecutions(List<TaskInstanceEntity> taskExecutions) {
        this.taskExecutions = taskExecutions;
    }

    public ErrorEntity getError() {
        return error;
    }

    public void setError(ErrorEntity error) {
        this.error = error;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(ZonedDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public ZonedDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(ZonedDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WorkflowInstanceEntity that = (WorkflowInstanceEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkflowInstanceEntity{" +
                "id='" + id + '\'' +
                ", namespace='" + namespace + '\'' +
                ", name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", status=" + status +
                ", start=" + start +
                ", end=" + end +
                '}';
    }
}
