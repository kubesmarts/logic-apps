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
 * Integer field filter for GraphQL queries.
 *
 * <p>Supports equality, comparison, and list inclusion operations.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   errorStatus: { eq: 500 }
 *   errorStatus: { gte: 400, lt: 500 }
 *   errorStatus: { in: [400, 404, 500] }
 * }
 * </pre>
 */
public class IntFilter {

    @Description("Equal to value")
    private Integer eq;

    @Description("Greater than")
    private Integer gt;

    @Description("Greater than or equal")
    private Integer gte;

    @Description("Less than")
    private Integer lt;

    @Description("Less than or equal")
    private Integer lte;

    @Description("In list of values")
    private List<Integer> in;

    public Integer getEq() {
        return eq;
    }

    public void setEq(Integer eq) {
        this.eq = eq;
    }

    public Integer getGt() {
        return gt;
    }

    public void setGt(Integer gt) {
        this.gt = gt;
    }

    public Integer getGte() {
        return gte;
    }

    public void setGte(Integer gte) {
        this.gte = gte;
    }

    public Integer getLt() {
        return lt;
    }

    public void setLt(Integer lt) {
        this.lt = lt;
    }

    public Integer getLte() {
        return lte;
    }

    public void setLte(Integer lte) {
        this.lte = lte;
    }

    public List<Integer> getIn() {
        return in;
    }

    public void setIn(List<Integer> in) {
        this.in = in;
    }
}
