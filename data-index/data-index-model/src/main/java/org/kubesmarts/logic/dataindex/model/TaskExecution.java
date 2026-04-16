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

    @JsonProperty("triggerTime")
    private ZonedDateTime enter;

    @JsonProperty("leaveTime")
    private ZonedDateTime exit;

    private String errorMessage;

    /**
     * TODO: Implement JSON scalar mapping for GraphQL
     */
    @Ignore
    private JsonNode inputArgs;

    /**
     * TODO: Implement JSON scalar mapping for GraphQL
     */
    @Ignore
    private JsonNode outputArgs;

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
                ", enter=" + enter +
                ", exit=" + exit +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
