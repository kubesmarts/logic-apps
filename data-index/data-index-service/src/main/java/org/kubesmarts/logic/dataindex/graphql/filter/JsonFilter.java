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

/**
 * JSON field filter for GraphQL queries.
 *
 * <p>Enables querying workflow/task input and output data fields using dot-notation.
 *
 * <p><b>PostgreSQL</b>: Uses JSONB operators (->>, @>)
 * <p><b>Elasticsearch</b>: Uses flattened field queries
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   input: {
 *     eq: [
 *       { key: "customerId", value: "customer-123" }
 *     ]
 *   }
 *   output: {
 *     eq: [
 *       { key: "status", value: "approved" },
 *       { key: "amount", value: "1000" }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <p><b>Implementation</b>:
 * <ul>
 *   <li>Key represents JSON field path (e.g., "customerId", "order.priority")
 *   <li>Value is converted to string for comparison
 *   <li>Nested paths use dot-notation: order.priority
 * </ul>
 */
public class JsonFilter {

    @Description("Equal to JSON field values (list of key-value pairs)")
    private List<JsonFieldFilter> eq;

    public List<JsonFieldFilter> getEq() {
        return eq;
    }

    public void setEq(List<JsonFieldFilter> eq) {
        this.eq = eq;
    }
}
