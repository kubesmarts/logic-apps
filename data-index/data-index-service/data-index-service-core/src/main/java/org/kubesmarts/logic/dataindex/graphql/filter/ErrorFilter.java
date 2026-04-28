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
 * Error field filter for GraphQL queries.
 *
 * <p>Used by both WorkflowInstanceFilter and TaskExecutionFilter to filter
 * by error fields.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   error: {
 *     type: { eq: "communication" }
 *     status: { gte: 500 }
 *     instance: { like: "do/0/*" }
 *   }
 * }
 * </pre>
 */
public class ErrorFilter {

    @Description("Filter by error type")
    private StringFilter type;

    @Description("Filter by error title")
    private StringFilter title;

    @Description("Filter by error detail")
    private StringFilter detail;

    @Description("Filter by error status code")
    private IntFilter status;

    @Description("Filter by error instance")
    private StringFilter instance;

    public StringFilter getType() {
        return type;
    }

    public void setType(StringFilter type) {
        this.type = type;
    }

    public StringFilter getTitle() {
        return title;
    }

    public void setTitle(StringFilter title) {
        this.title = title;
    }

    public StringFilter getDetail() {
        return detail;
    }

    public void setDetail(StringFilter detail) {
        this.detail = detail;
    }

    public IntFilter getStatus() {
        return status;
    }

    public void setStatus(IntFilter status) {
        this.status = status;
    }

    public StringFilter getInstance() {
        return instance;
    }

    public void setInstance(StringFilter instance) {
        this.instance = instance;
    }
}
