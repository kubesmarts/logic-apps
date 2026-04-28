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
import java.util.Objects;

import org.eclipse.microprofile.graphql.Ignore;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runtime execution of a workflow task.
 *
 * <p>Represents an execution instance of a task within a workflow instance.
 * Tasks in Serverless Workflow 1.0.0 include: call, do, fork, emit, for, try, etc.
 *
 * <p>The taskPosition field is critical - it's a JSONPointer that uniquely identifies
 * the task within the workflow definition (e.g., "/do/0" for first task in a do sequence).
 */
public class TaskExecution {

    private String id;
    private String taskName;

    /**
     * JSONPointer identifying the task's position in the workflow definition.
     * Example: "/do/0", "/do/1/then/0", etc.
     * This is the unique identifier for the task within the workflow document.
     */
    private String taskPosition;

    /**
     * Task execution status.
     * Values: RUNNING, COMPLETED, FAULTED
     */
    private String status;

    @JsonProperty("startDate")
    private ZonedDateTime start;

    @JsonProperty("endDate")
    private ZonedDateTime end;

    private Error error;

    /**
     * Task input data (internal).
     * <p>Hidden from GraphQL - use getInputData() instead
     */
    @Ignore
    private JsonNode input;

    /**
     * Task output data (internal).
     * <p>Hidden from GraphQL - use getOutputData() instead
     */
    @Ignore
    private JsonNode output;

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

    public Error getError() {
        return error;
    }

    public void setError(Error error) {
        this.error = error;
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

    /**
     * Get input data as JSON string for GraphQL.
     * @return JSON string or null if no input
     */
    @JsonProperty("inputData")
    public String getInputData() {
        return input != null ? input.toString() : null;
    }

    /**
     * Get output data as JSON string for GraphQL.
     * @return JSON string or null if no output
     */
    @JsonProperty("outputData")
    public String getOutputData() {
        return output != null ? output.toString() : null;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskExecution that = (TaskExecution) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TaskExecution{" +
                "id='" + id + '\'' +
                ", taskName='" + taskName + '\'' +
                ", taskPosition='" + taskPosition + '\'' +
                ", status='" + status + '\'' +
                ", start=" + start +
                ", end=" + end +
                ", error=" + error +
                '}';
    }
}
