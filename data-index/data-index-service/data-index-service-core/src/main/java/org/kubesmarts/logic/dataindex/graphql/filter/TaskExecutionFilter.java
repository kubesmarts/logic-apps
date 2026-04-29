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
import org.kubesmarts.logic.dataindex.graphql.filter.ErrorFilter;

/**
 * TaskExecution filter for GraphQL queries.
 *
 * <p>Supports filtering task executions by various fields including JSON input/output data.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * query {
 *   getTaskExecutions(
 *     filter: {
 *       taskName: { eq: "callPaymentService" }
 *       enter: { gte: "2026-01-01T00:00:00Z" }
 *       inputArgs: {
 *         eq: { customerId: "customer-123" }
 *       }
 *     }
 *     limit: 50
 *     offset: 0
 *   ) {
 *     id
 *     taskName
 *     taskPosition
 *     enter
 *     exit
 *     inputArgs
 *     outputArgs
 *   }
 * }
 * </pre>
 */
public class TaskExecutionFilter {

    @Description("Filter by task execution ID")
    private StringFilter id;

    @Description("Filter by task name")
    private StringFilter taskName;

    @Description("Filter by task position (JSONPointer)")
    private StringFilter taskPosition;

    @Description("Filter by enter time")
    private DateTimeFilter enter;

    @Description("Filter by exit time")
    private DateTimeFilter exit;

    @Description("Filter by error fields")
    private ErrorFilter error;

    @Description("Filter by input arguments fields")
    private JsonFilter inputArgs;

    @Description("Filter by output arguments fields")
    private JsonFilter outputArgs;

    public StringFilter getId() {
        return id;
    }

    public void setId(StringFilter id) {
        this.id = id;
    }

    public StringFilter getTaskName() {
        return taskName;
    }

    public void setTaskName(StringFilter taskName) {
        this.taskName = taskName;
    }

    public StringFilter getTaskPosition() {
        return taskPosition;
    }

    public void setTaskPosition(StringFilter taskPosition) {
        this.taskPosition = taskPosition;
    }

    public DateTimeFilter getEnter() {
        return enter;
    }

    public void setEnter(DateTimeFilter enter) {
        this.enter = enter;
    }

    public DateTimeFilter getExit() {
        return exit;
    }

    public void setExit(DateTimeFilter exit) {
        this.exit = exit;
    }

    public ErrorFilter getError() {
        return error;
    }

    public void setError(ErrorFilter error) {
        this.error = error;
    }

    public JsonFilter getInputArgs() {
        return inputArgs;
    }

    public void setInputArgs(JsonFilter inputArgs) {
        this.inputArgs = inputArgs;
    }

    public JsonFilter getOutputArgs() {
        return outputArgs;
    }

    public void setOutputArgs(JsonFilter outputArgs) {
        this.outputArgs = outputArgs;
    }
}
