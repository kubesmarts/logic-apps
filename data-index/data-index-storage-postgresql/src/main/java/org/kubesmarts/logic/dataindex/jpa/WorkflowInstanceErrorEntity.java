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
package org.kubesmarts.logic.dataindex.jpa;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * JPA entity for workflow instance error information.
 *
 * <p><b>Design principle:</b> This entity stores data from the error object in
 * workflow.instance.faulted events. It aligns with the Serverless Workflow 1.0.0 Error spec.
 *
 * <p><b>Event source:</b>
 * <ul>
 *   <li>workflow.instance.faulted → error object with type, title, detail, status, instance
 * </ul>
 *
 * <p>Maps to WorkflowInstanceError domain model.
 *
 * <p>Embedded in WorkflowInstanceEntity (not a separate table).
 */
@Embeddable
public class WorkflowInstanceErrorEntity {

    /**
     * Error type classification.
     * <p>Source: error.type from workflow.instance.faulted event
     * <p>SW 1.0.0 values: "system", "business", "timeout", "communication"
     */
    @Column(name = "error_type")
    private String type;

    /**
     * Error title - short summary.
     * <p>Source: error.title from workflow.instance.faulted event
     */
    @Column(name = "error_title")
    private String title;

    /**
     * Detailed error message or stack trace.
     * <p>Source: error.detail from workflow.instance.faulted event
     */
    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String detail;

    /**
     * HTTP status code (if applicable).
     * <p>Source: error.status from workflow.instance.faulted event
     */
    @Column(name = "error_status")
    private Integer status;

    /**
     * Error instance identifier for traceability.
     * <p>Source: error.instance from workflow.instance.faulted event
     */
    @Column(name = "error_instance")
    private String instance;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WorkflowInstanceErrorEntity that = (WorkflowInstanceErrorEntity) o;
        return Objects.equals(type, that.type) &&
                Objects.equals(title, that.title) &&
                Objects.equals(detail, that.detail) &&
                Objects.equals(status, that.status) &&
                Objects.equals(instance, that.instance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, title, detail, status, instance);
    }

    @Override
    public String toString() {
        return "WorkflowInstanceErrorEntity{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", status=" + status +
                ", instance='" + instance + '\'' +
                '}';
    }
}
