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
package org.kubesmarts.logic.dataindex.entity;

import java.time.ZonedDateTime;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * JPA entity for task execution instances.
 *
 * <p><b>Design principle:</b> This entity stores data from Quarkus Flow task lifecycle events.
 * Every field maps directly to data emitted in task events.
 *
 * <p><b>Event sources:</b>
 * <ul>
 *   <li>workflow.task.started → id, taskName, taskPosition, enter, inputArgs
 *   <li>workflow.task.completed → exit, outputArgs
 *   <li>workflow.task.faulted → exit, errorMessage
 * </ul>
 *
 * <p>Maps to TaskExecution domain model.
 */
@Entity
@Table(name = "task_executions")
public class TaskExecutionEntity extends AbstractEntity {

    /**
     * Task execution ID.
     * <p>Source: taskExecutionId from Quarkus Flow events (generated deterministically)
     * <p>Generation: UUID based on instanceId + taskPosition + timestamp
     */
    @Id
    private String id;

    /**
     * Task name.
     * <p>Source: taskName from Quarkus Flow task events
     */
    private String taskName;

    /**
     * Task position in workflow document (JSONPointer).
     * <p>Source: taskPosition from Quarkus Flow task events
     * <p>Examples: "/do/0", "/fork/branches/0/do/1", "/do/1/then/0"
     * <p><b>Critical:</b> This is the unique identifier for tasks in SW 1.0.0
     */
    private String taskPosition;

    /**
     * Task execution start time.
     * <p>Source: startTime from workflow.task.started event
     */
    private ZonedDateTime enter;

    /**
     * Task execution end time.
     * <p>Source: endTime from workflow.task.completed or workflow.task.faulted events
     */
    private ZonedDateTime exit;

    /**
     * Error message if task failed.
     * <p>Source: error.title from workflow.task.faulted event
     */
    private String errorMessage;

    /**
     * Input arguments (JSON).
     * <p>Source: input from workflow.task.started event
     * <p>Stored as JSONB in PostgreSQL
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode inputArgs;

    /**
     * Output arguments (JSON).
     * <p>Source: output from workflow.task.completed event
     * <p>Stored as JSONB in PostgreSQL
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode outputArgs;

    /**
     * Reference to parent workflow instance.
     * <p>Source: instanceId from workflow.task.* events (foreign key relationship)
     */
    @ManyToOne(cascade = CascadeType.ALL, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "workflow_instance_id", foreignKey = @ForeignKey(name = "fk_task_executions_workflow_instance"))
    private WorkflowInstanceEntity workflowInstance;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public ZonedDateTime getEnter() {
        return enter;
    }

    public void setEnter(ZonedDateTime enter) {
        this.enter = enter;
    }

    public ZonedDateTime getExit() {
        return exit;
    }

    public void setExit(ZonedDateTime exit) {
        this.exit = exit;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public JsonNode getInputArgs() {
        return inputArgs;
    }

    public void setInputArgs(JsonNode inputArgs) {
        this.inputArgs = inputArgs;
    }

    public JsonNode getOutputArgs() {
        return outputArgs;
    }

    public void setOutputArgs(JsonNode outputArgs) {
        this.outputArgs = outputArgs;
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
        TaskExecutionEntity that = (TaskExecutionEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TaskExecutionEntity{" +
                "id='" + id + '\'' +
                ", taskName='" + taskName + '\'' +
                ", taskPosition='" + taskPosition + '\'' +
                ", enter=" + enter +
                ", exit=" + exit +
                '}';
    }
}
