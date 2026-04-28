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

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Error information for failed workflow and task executions.
 *
 * <p>Represents a runtime error from Serverless Workflow 1.0.0 execution.
 * Aligns with the <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#error">SW 1.0.0 Error spec</a>
 *
 * <p>This captures error details from Quarkus Flow structured logging events
 * (workflow.instance.faulted, workflow.task.faulted).
 *
 * <p>Used by both WorkflowInstance and TaskExecution.
 */
public class Error {

    /**
     * Error type classification (e.g., "system", "business", "timeout", "communication").
     * Categorizes the error according to SW 1.0.0 specification.
     */
    private String type;

    /**
     * Error title - short summary of the error.
     */
    private String title;

    /**
     * Detailed error message or stack trace.
     */
    private String detail;

    /**
     * HTTP status code associated with the error (if applicable).
     */
    private Integer status;

    /**
     * Error instance identifier for traceability.
     */
    private String instance;

    public Error() {
    }

    public Error(String type, String title) {
        this.type = type;
        this.title = title;
    }

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
        Error that = (Error) o;
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
        return "Error{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", detail='" + detail + '\'' +
                ", status=" + status +
                ", instance='" + instance + '\'' +
                '}';
    }
}
