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

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Objects;

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
 * JPA entity for workflow instance events.
 *
 * <p><b>Pattern</b>: Transactional Outbox (append-only event log)
 *
 * <p><b>Design</b>: This entity stores events from Quarkus Flow structured logging,
 * written by FluentBit after flattening nested JSON into structured columns.
 *
 * <p><b>Event Sources</b>:
 * <ul>
 *   <li>workflow.started → namespace, name, version, start_time, input_data
 *   <li>workflow.completed → end_time, output_data
 *   <li>workflow.faulted → error_type, error_title, error_detail, error_status
 * </ul>
 *
 * <p><b>Processing</b>: Scheduled processor polls for unprocessed events,
 * merges them into WorkflowInstanceEntity (final table), marks as processed.
 *
 * <p><b>Cleanup</b>: Daily job deletes processed events older than retention period (default 30 days).
 *
 * @see org.kubesmarts.logic.dataindex.entity.WorkflowInstanceEntity
 */
@Entity
@Table(name = "workflow_instance_events", indexes = {
        @Index(name = "idx_wi_events_unprocessed", columnList = "processed, event_time"),
        @Index(name = "idx_wi_events_cleanup", columnList = "processed, processed_at"),
        @Index(name = "idx_wi_events_instance", columnList = "instance_id, event_time")
})
public class WorkflowInstanceEvent implements Serializable {

    /**
     * Event ID (auto-generated).
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    /**
     * Event type: 'started', 'completed', 'faulted'.
     * <p>Extracted from full event type (e.g., "io.serverlessworkflow.workflow.started.v1" → "started")
     */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /**
     * Event timestamp from Quarkus Flow.
     */
    @Column(name = "event_time", nullable = false)
    private ZonedDateTime eventTime;

    /**
     * Workflow instance ID.
     * <p>Links multiple events for the same instance.
     */
    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    // ========================================================================
    // Fields from 'started' event
    // ========================================================================

    /**
     * Workflow namespace (SW 1.0.0).
     * <p>Only present in 'started' event.
     */
    @Column(name = "workflow_namespace")
    private String workflowNamespace;

    /**
     * Workflow name.
     * <p>Only present in 'started' event.
     */
    @Column(name = "workflow_name")
    private String workflowName;

    /**
     * Workflow version.
     * <p>Only present in 'started' event.
     */
    @Column(name = "workflow_version")
    private String workflowVersion;

    /**
     * Workflow start time.
     * <p>Only present in 'started' event.
     */
    @Column(name = "start_time")
    private ZonedDateTime startTime;

    /**
     * Workflow input data (JSONB).
     * <p>Only present in 'started' event.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_data", columnDefinition = "jsonb")
    private JsonNode inputData;

    // ========================================================================
    // Fields from 'completed' event
    // ========================================================================

    /**
     * Workflow end time.
     * <p>Only present in 'completed' event.
     */
    @Column(name = "end_time")
    private ZonedDateTime endTime;

    /**
     * Workflow output data (JSONB).
     * <p>Only present in 'completed' event.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_data", columnDefinition = "jsonb")
    private JsonNode outputData;

    // ========================================================================
    // Fields from 'faulted' event
    // ========================================================================

    /**
     * Error type URI (SW 1.0.0 Error spec).
     * <p>Only present in 'faulted' event.
     */
    @Column(name = "error_type")
    private String errorType;

    /**
     * Error title (SW 1.0.0 Error spec).
     * <p>Only present in 'faulted' event.
     */
    @Column(name = "error_title")
    private String errorTitle;

    /**
     * Error detail (SW 1.0.0 Error spec).
     * <p>Only present in 'faulted' event.
     */
    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    /**
     * Error HTTP status code (SW 1.0.0 Error spec).
     * <p>Only present in 'faulted' event.
     */
    @Column(name = "error_status")
    private Integer errorStatus;

    // ========================================================================
    // Common fields
    // ========================================================================

    /**
     * Workflow status: RUNNING, COMPLETED, FAULTED, etc.
     * <p>Present in all events.
     */
    @Column(name = "status")
    private String status;

    // ========================================================================
    // Processing metadata
    // ========================================================================

    /**
     * Has this event been processed?
     * <p>Set to true by event processor after merging into final table.
     */
    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    /**
     * When was this event processed?
     * <p>Set by event processor.
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

    public String getWorkflowNamespace() {
        return workflowNamespace;
    }

    public void setWorkflowNamespace(String workflowNamespace) {
        this.workflowNamespace = workflowNamespace;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(String workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(ZonedDateTime startTime) {
        this.startTime = startTime;
    }

    public JsonNode getInputData() {
        return inputData;
    }

    public void setInputData(JsonNode inputData) {
        this.inputData = inputData;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(ZonedDateTime endTime) {
        this.endTime = endTime;
    }

    public JsonNode getOutputData() {
        return outputData;
    }

    public void setOutputData(JsonNode outputData) {
        this.outputData = outputData;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getErrorTitle() {
        return errorTitle;
    }

    public void setErrorTitle(String errorTitle) {
        this.errorTitle = errorTitle;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public Integer getErrorStatus() {
        return errorStatus;
    }

    public void setErrorStatus(Integer errorStatus) {
        this.errorStatus = errorStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
        WorkflowInstanceEvent that = (WorkflowInstanceEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "WorkflowInstanceEvent{" +
                "eventId=" + eventId +
                ", eventType='" + eventType + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", eventTime=" + eventTime +
                ", processed=" + processed +
                '}';
    }
}
