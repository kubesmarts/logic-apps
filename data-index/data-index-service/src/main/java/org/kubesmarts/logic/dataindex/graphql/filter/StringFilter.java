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
 * String field filter for GraphQL queries.
 *
 * <p>Supports equality, pattern matching, and list inclusion.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   name: { eq: "greeting-workflow" }
 *   namespace: { like: "prod-*" }
 *   version: { in: ["1.0", "1.1"] }
 * }
 * </pre>
 */
public class StringFilter {

    @Description("Equal to value")
    private String eq;

    @Description("Like pattern (* for wildcard)")
    private String like;

    @Description("In list of values")
    private List<String> in;

    public String getEq() {
        return eq;
    }

    public void setEq(String eq) {
        this.eq = eq;
    }

    public String getLike() {
        return like;
    }

    public void setLike(String like) {
        this.like = like;
    }

    public List<String> getIn() {
        return in;
    }

    public void setIn(List<String> in) {
        this.in = in;
    }
}
