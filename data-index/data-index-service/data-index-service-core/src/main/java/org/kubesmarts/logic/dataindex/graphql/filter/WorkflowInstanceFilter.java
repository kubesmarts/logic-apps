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
package org.kubesmarts.logic.dataindex.graphql.filter;

import org.eclipse.microprofile.graphql.Description;

/**
 * WorkflowInstance filter for GraphQL queries.
 *
 * <p>Supports filtering workflow instances by various fields including JSON input/output data.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * query {
 *   getWorkflowInstances(
 *     filter: {
 *       status: { eq: COMPLETED }
 *       namespace: { eq: "production" }
 *       startTime: { gte: "2026-01-01T00:00:00Z" }
 *       error: {
 *         type: { eq: "communication" }
 *         status: { gte: 500 }
 *       }
 *     }
 *     limit: 50
 *     offset: 0
 *   ) {
 *     id
 *     name
 *     status
 *     input
 *     output
 *   }
 * }
 * </pre>
 */
public class WorkflowInstanceFilter {

    @Description("Filter by instance ID")
    private StringFilter id;

    @Description("Filter by workflow name")
    private StringFilter name;

    @Description("Filter by namespace")
    private StringFilter namespace;

    @Description("Filter by version")
    private StringFilter version;

    @Description("Filter by status")
    private WorkflowInstanceStatusFilter status;

    @Description("Filter by start time")
    private DateTimeFilter startTime;

    @Description("Filter by end time")
    private DateTimeFilter endTime;

    @Description("Filter by input data fields")
    private JsonFilter input;

    @Description("Filter by output data fields")
    private JsonFilter output;

    @Description("Filter by error fields")
    private ErrorFilter error;

    public StringFilter getId() {
        return id;
    }

    public void setId(StringFilter id) {
        this.id = id;
    }

    public StringFilter getName() {
        return name;
    }

    public void setName(StringFilter name) {
        this.name = name;
    }

    public StringFilter getNamespace() {
        return namespace;
    }

    public void setNamespace(StringFilter namespace) {
        this.namespace = namespace;
    }

    public StringFilter getVersion() {
        return version;
    }

    public void setVersion(StringFilter version) {
        this.version = version;
    }

    public WorkflowInstanceStatusFilter getStatus() {
        return status;
    }

    public void setStatus(WorkflowInstanceStatusFilter status) {
        this.status = status;
    }

    public DateTimeFilter getStartTime() {
        return startTime;
    }

    public void setStartTime(DateTimeFilter startTime) {
        this.startTime = startTime;
    }

    public DateTimeFilter getEndTime() {
        return endTime;
    }

    public void setEndTime(DateTimeFilter endTime) {
        this.endTime = endTime;
    }

    public JsonFilter getInput() {
        return input;
    }

    public void setInput(JsonFilter input) {
        this.input = input;
    }

    public JsonFilter getOutput() {
        return output;
    }

    public void setOutput(JsonFilter output) {
        this.output = output;
    }

    public ErrorFilter getError() {
        return error;
    }

    public void setError(ErrorFilter error) {
        this.error = error;
    }
}
