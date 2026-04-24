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
 * WorkflowInstance ordering for GraphQL queries.
 *
 * <p>Supports sorting by workflow instance fields.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * orderBy: {
 *   startTime: DESC
 *   name: ASC
 * }
 * </pre>
 */
public class WorkflowInstanceOrderBy {

    @Description("Order by instance ID")
    private OrderBy id;

    @Description("Order by workflow name")
    private OrderBy name;

    @Description("Order by namespace")
    private OrderBy namespace;

    @Description("Order by version")
    private OrderBy version;

    @Description("Order by status")
    private OrderBy status;

    @Description("Order by start time")
    private OrderBy startTime;

    @Description("Order by end time")
    private OrderBy endTime;

    @Description("Order by last update time")
    private OrderBy lastUpdate;

    public OrderBy getId() {
        return id;
    }

    public void setId(OrderBy id) {
        this.id = id;
    }

    public OrderBy getName() {
        return name;
    }

    public void setName(OrderBy name) {
        this.name = name;
    }

    public OrderBy getNamespace() {
        return namespace;
    }

    public void setNamespace(OrderBy namespace) {
        this.namespace = namespace;
    }

    public OrderBy getVersion() {
        return version;
    }

    public void setVersion(OrderBy version) {
        this.version = version;
    }

    public OrderBy getStatus() {
        return status;
    }

    public void setStatus(OrderBy status) {
        this.status = status;
    }

    public OrderBy getStartTime() {
        return startTime;
    }

    public void setStartTime(OrderBy startTime) {
        this.startTime = startTime;
    }

    public OrderBy getEndTime() {
        return endTime;
    }

    public void setEndTime(OrderBy endTime) {
        this.endTime = endTime;
    }

    public OrderBy getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(OrderBy lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
