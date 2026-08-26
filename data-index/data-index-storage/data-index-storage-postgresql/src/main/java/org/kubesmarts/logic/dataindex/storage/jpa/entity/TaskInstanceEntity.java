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
package org.kubesmarts.logic.dataindex.storage.jpa.entity;

import java.time.ZonedDateTime;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * JPA entity for task instances.
 *
 * <p><b>Design principle:</b> This entity stores normalized task data populated by
 * PostgreSQL triggers from raw events. Maps to the {@code task_instances} table.
 *
 * <p><b>Data Source:</b> PostgreSQL triggers extract from {@code task_events_raw.data} JSONB
 * and UPSERT into this table.
 *
 * <p><b>Event sources (via triggers):</b>
 * <ul>
 *   <li>workflow.task.started → taskName, taskPosition, start, input, status
 *   <li>workflow.task.completed → end, output, status
 *   <li>workflow.task.faulted → end, status
 * </ul>
 *
 * <p>Maps to TaskExecution domain model.
 * <p>Uses @EmbeddedId for composite primary key to better handle FK on PK column scenario.
 */
@Entity
@Table(name = "task_instances")
public class TaskInstanceEntity extends AbstractEntity {

    /**
     * Composite primary key (instance_id, task_position).
     * <p>Using @EmbeddedId instead of @IdClass for better JPA handling when
     * instance_id is also a foreign key column.
     */
    @EmbeddedId
    private TaskInstanceEntityId id;

    /**
     * Task name.
     * <p>Source: taskName from Quarkus Flow task events
     * <p>Extracted by trigger from: data->>'taskName'
     */
    private String taskName;

    /**
     * Task instance status.
     * <p>Source: status from Quarkus Flow events
     * <p>Extracted by trigger from: data->>'status'
     * <p>Values: RUNNING, COMPLETED, FAULTED
     */
    private String status;

    /**
     * Task execution start time.
     * <p>Source: startTime from workflow.task.started event
     * <p>Extracted by trigger from: to_timestamp((data->>'startTime')::numeric)
     */
    @Column(name = "\"start\"")
    private ZonedDateTime start;

    /**
     * Task execution end time.
     * <p>Source: endTime from workflow.task.completed or workflow.task.faulted events
     * <p>Extracted by trigger from: to_timestamp((data->>'endTime')::numeric)
     */
    @Column(name = "\"end\"")
    private ZonedDateTime end;

    /**
     * Input data (JSONB).
     * <p>Source: input from workflow.task.started event
     * <p>Extracted by trigger from: data->'input'
     * <p>Stored as JSONB in PostgreSQL
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;

    /**
     * Output data (JSONB).
     * <p>Source: output from workflow.task.completed event
     * <p>Extracted by trigger from: data->'output'
     * <p>Stored as JSONB in PostgreSQL
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;

    /**
     * Error information if task failed.
     * <p>Source: error object from workflow.task.faulted event
     * <p>Extracted by trigger from: data->'error'
     * <p>Stored in error_* columns
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

    /**
     * Reference to parent workflow instance.
     * <p>Foreign key relationship to workflow_instances table
     * <p>Note: instance_id is also part of composite PK, so relationship uses referencedColumnName
     */
    @ManyToOne
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "instance_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_task_instance_workflow"), insertable = false, updatable = false)
    private WorkflowInstanceEntity workflowInstance;

    @Override
    public String getId() {
        // Return derived ID from composite key
        if (id != null && id.getInstanceId() != null && id.getTaskPosition() != null) {
            return id.getInstanceId() + ":" + id.getTaskPosition();
        }
        return null;
    }

    public TaskInstanceEntityId getCompositeId() {
        return id;
    }

    public void setCompositeId(TaskInstanceEntityId id) {
        this.id = id;
    }

    // Convenience getters/setters for composite key parts (for backward compatibility)
    public String getInstanceId() {
        return id != null ? id.getInstanceId() : null;
    }

    public void setInstanceId(String instanceId) {
        if (this.id == null) {
            this.id = new TaskInstanceEntityId();
        }
        this.id.setInstanceId(instanceId);
    }

    public String getTaskPosition() {
        return id != null ? id.getTaskPosition() : null;
    }

    public void setTaskPosition(String taskPosition) {
        if (this.id == null) {
            this.id = new TaskInstanceEntityId();
        }
        this.id.setTaskPosition(taskPosition);
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

    public WorkflowInstanceEntity getWorkflowInstance() {
        return workflowInstance;
    }

    public void setWorkflowInstance(WorkflowInstanceEntity workflowInstance) {
        this.workflowInstance = workflowInstance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskInstanceEntity that = (TaskInstanceEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TaskInstanceEntity{" +
                "id=" + id +
                ", taskName='" + taskName + '\'' +
                ", status='" + status + '\'' +
                ", start=" + start +
                ", end=" + end +
                ", error=" + error +
                '}';
    }
}
