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
package org.kubesmarts.logic.dataindex.event;

import java.time.ZonedDateTime;
import java.util.Objects;

import java.io.Serializable;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * JPA entity for task execution events.
 *
 * <p><b>Pattern</b>: Transactional Outbox (append-only event log)
 *
 * <p><b>Design</b>: This entity stores events from Quarkus Flow structured logging,
 * written by FluentBit after flattening nested JSON into structured columns.
 *
 * <p><b>CRITICAL</b>: Tasks are identified by taskPosition (JSONPointer), NOT taskExecutionId!
 * The Quarkus Flow SDK generates different taskExecutionId for started vs completed events.
 * Processor merges events by (instance_id, task_position).
 *
 * <p><b>Task Position Examples</b>:
 * <ul>
 *   <li>"do/0" - First task in do sequence
 *   <li>"do/1" - Second task in do sequence
 *   <li>"fork/branches/0/do/0" - First task in first fork branch
 * </ul>
 *
 * <p><b>Event Sources</b>:
 * <ul>
 *   <li>task.started → task_position, task_name, start_time, input_args
 *   <li>task.completed → end_time, output_args
 *   <li>task.faulted → end_time, error_message
 * </ul>
 *
 * <p><b>Processing</b>: Scheduled processor polls for unprocessed events,
 * groups by (instance_id, task_position), merges into TaskExecutionEntity.
 *
 * @see org.kubesmarts.logic.dataindex.entity.TaskExecutionEntity
 */
@Entity
@Table(name = "task_execution_events", indexes = {
        @Index(name = "idx_te_events_unprocessed", columnList = "processed, event_time"),
        @Index(name = "idx_te_events_cleanup", columnList = "processed, processed_at"),
        @Index(name = "idx_te_events_instance", columnList = "instance_id, event_time"),
        @Index(name = "idx_te_events_position", columnList = "instance_id, task_position, event_time")
})
public class TaskExecutionEvent implements Serializable {

    /**
     * Event ID (auto-generated).
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /**
     * Event type: 'started', 'completed', 'faulted'.
     */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /**
     * Event timestamp from Quarkus Flow.
     */
    @Column(name = "event_time", nullable = false)
    private ZonedDateTime eventTime;

    /**
     * Parent workflow instance ID.
     */
    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    /**
     * Task execution ID from event.
     * <p><b>WARNING</b>: This changes between started/completed events!
     * Use taskPosition for merging, not this ID.
     */
    @Column(name = "task_execution_id", nullable = false)
    private String taskExecutionId;

    /**
     * Task position (JSONPointer).
     * <p><b>CRITICAL</b>: This is the stable identifier for tasks.
     * Use this for merging events, not taskExecutionId.
     * <p>Examples: "do/0", "do/1", "fork/branches/0/do/0"
     */
    @Column(name = "task_position")
    private String taskPosition;

    /**
     * Task name from workflow definition.
     */
    @Column(name = "task_name")
    private String taskName;

    // ========================================================================
    // Fields from 'started' event
    // ========================================================================

    /**
     * Task start time.
     * <p>Only present in 'started' event.
     */
    @Column(name = "start_time")
    private ZonedDateTime startTime;

    /**
     * Task input arguments (JSONB).
     * <p>Only present in 'started' event.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_args", columnDefinition = "jsonb")
    private JsonNode inputArgs;

    // ========================================================================
    // Fields from 'completed' event
    // ========================================================================

    /**
     * Task end time.
     * <p>Present in 'completed' and 'faulted' events.
     */
    @Column(name = "end_time")
    private ZonedDateTime endTime;

    /**
     * Task output arguments (JSONB).
     * <p>Only present in 'completed' event.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_args", columnDefinition = "jsonb")
    private JsonNode outputArgs;

    // ========================================================================
    // Fields from 'faulted' event
    // ========================================================================

    /**
     * Error message if task failed.
     * <p>Only present in 'faulted' event.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ========================================================================
    // Processing metadata
    // ========================================================================

    /**
     * Has this event been processed?
     */
    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    /**
     * When was this event processed?
     */
    @Column(name = "processed_at")
    private ZonedDateTime processedAt;

    // ========================================================================
    // Getters and Setters
    // ========================================================================

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public ZonedDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(ZonedDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getTaskExecutionId() {
        return taskExecutionId;
    }

    public void setTaskExecutionId(String taskExecutionId) {
        this.taskExecutionId = taskExecutionId;
    }

    public String getTaskPosition() {
        return taskPosition;
    }

    public void setTaskPosition(String taskPosition) {
        this.taskPosition = taskPosition;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(ZonedDateTime startTime) {
        this.startTime = startTime;
    }

    public JsonNode getInputArgs() {
        return inputArgs;
    }

    public void setInputArgs(JsonNode inputArgs) {
        this.inputArgs = inputArgs;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(ZonedDateTime endTime) {
        this.endTime = endTime;
    }

    public JsonNode getOutputArgs() {
        return outputArgs;
    }

    public void setOutputArgs(JsonNode outputArgs) {
        this.outputArgs = outputArgs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Boolean getProcessed() {
        return processed;
    }

    public void setProcessed(Boolean processed) {
        this.processed = processed;
    }

    public ZonedDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(ZonedDateTime processedAt) {
        this.processedAt = processedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskExecutionEvent that = (TaskExecutionEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "TaskExecutionEvent{" +
                "eventId=" + eventId +
                ", eventType='" + eventType + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", taskPosition='" + taskPosition + '\'' +
                ", taskName='" + taskName + '\'' +
                ", eventTime=" + eventTime +
                ", processed=" + processed +
                '}';
    }
}
