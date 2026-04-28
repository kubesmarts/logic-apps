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
 * TaskExecution ordering for GraphQL queries.
 *
 * <p>Supports sorting by task execution fields.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * orderBy: {
 *   enter: DESC
 *   taskName: ASC
 * }
 * </pre>
 */
public class TaskExecutionOrderBy {

    @Description("Order by task execution ID")
    private OrderBy id;

    @Description("Order by task name")
    private OrderBy taskName;

    @Description("Order by task position")
    private OrderBy taskPosition;

    @Description("Order by enter time")
    private OrderBy enter;

    @Description("Order by exit time")
    private OrderBy exit;

    public OrderBy getId() {
        return id;
    }

    public void setId(OrderBy id) {
        this.id = id;
    }

    public OrderBy getTaskName() {
        return taskName;
    }

    public void setTaskName(OrderBy taskName) {
        this.taskName = taskName;
    }

    public OrderBy getTaskPosition() {
        return taskPosition;
    }

    public void setTaskPosition(OrderBy taskPosition) {
        this.taskPosition = taskPosition;
    }

    public OrderBy getEnter() {
        return enter;
    }

    public void setEnter(OrderBy enter) {
        this.enter = enter;
    }

    public OrderBy getExit() {
        return exit;
    }

    public void setExit(OrderBy exit) {
        this.exit = exit;
    }
}
