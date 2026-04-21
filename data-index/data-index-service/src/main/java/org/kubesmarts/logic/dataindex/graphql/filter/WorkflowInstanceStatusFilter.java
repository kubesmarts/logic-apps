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

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

/**
 * WorkflowInstanceStatus enum filter for GraphQL queries.
 *
 * <p>Supports equality and list inclusion for workflow status.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   status: { eq: COMPLETED }
 *   status: { in: [COMPLETED, FAULTED] }
 * }
 * </pre>
 */
public class WorkflowInstanceStatusFilter {

    @Description("Equal to status")
    private WorkflowInstanceStatus eq;

    @Description("In list of statuses")
    private List<WorkflowInstanceStatus> in;

    public WorkflowInstanceStatus getEq() {
        return eq;
    }

    public void setEq(WorkflowInstanceStatus eq) {
        this.eq = eq;
    }

    public List<WorkflowInstanceStatus> getIn() {
        return in;
    }

    public void setIn(List<WorkflowInstanceStatus> in) {
        this.in = in;
    }
}
