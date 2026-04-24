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
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
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
 *   <li>workflow.task.started → taskExecutionId, taskName, taskPosition, start, input, status
 *   <li>workflow.task.completed → end, output, status
 *   <li>workflow.task.faulted → end, status
 * </ul>
 *
 * <p>Maps to TaskInstance domain model.
 */
@Entity
@Table(name = "task_instances")
public class TaskInstanceEntity extends AbstractEntity {

    /**
     * Task execution ID (primary key).
     * <p>Source: taskExecutionId from Quarkus Flow events
     * <p>Extracted by trigger from: data->>'taskExecutionId'
     */
    @Id
    @Column(name = "task_execution_id")
    private String taskExecutionId;

    /**
     * Workflow instance ID (foreign key).
     * <p>Source: instanceId from Quarkus Flow events
     * <p>Extracted by trigger from: data->>'instanceId'
     */
    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    /**
     * Task name.
     * <p>Source: taskName from Quarkus Flow task events
     * <p>Extracted by trigger from: data->>'taskName'
     */
    private String taskName;

    /**
     * Task position in workflow document (JSONPointer).
     * <p>Source: taskPosition from Quarkus Flow task events
     * <p>Extracted by trigger from: data->>'taskPosition'
     * <p>Examples: "do/0/set-0", "fork/branches/0/do/1"
     */
    private String taskPosition;

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
     */
    @ManyToOne
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "instance_id", foreignKey = @ForeignKey(name = "fk_task_instance_workflow"), insertable = false, updatable = false)
    private WorkflowInstanceEntity workflowInstance;

    @Override
    public String getId() {
        return taskExecutionId;
    }

    public String getTaskExecutionId() {
        return taskExecutionId;
    }

    public void setTaskExecutionId(String taskExecutionId) {
        this.taskExecutionId = taskExecutionId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getTaskPosition() {
        return taskPosition;
    }

    public void setTaskPosition(String taskPosition) {
        this.taskPosition = taskPosition;
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
        return Objects.equals(taskExecutionId, that.taskExecutionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskExecutionId);
    }

    @Override
    public String toString() {
        return "TaskInstanceEntity{" +
                "taskExecutionId='" + taskExecutionId + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", taskName='" + taskName + '\'' +
                ", taskPosition='" + taskPosition + '\'' +
                ", status='" + status + '\'' +
                ", start=" + start +
                ", end=" + end +
                '}';
    }
}
