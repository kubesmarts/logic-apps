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

import java.time.ZonedDateTime;

import org.eclipse.microprofile.graphql.Description;

/**
 * DateTime field filter for GraphQL queries.
 *
 * <p>Supports comparison operations for timestamp fields.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   startTime: { gte: "2026-01-01T00:00:00Z" }
 *   endTime: { lt: "2026-12-31T23:59:59Z" }
 * }
 * </pre>
 */
public class DateTimeFilter {

    @Description("Equal to timestamp")
    private ZonedDateTime eq;

    @Description("Greater than timestamp")
    private ZonedDateTime gt;

    @Description("Greater than or equal to timestamp")
    private ZonedDateTime gte;

    @Description("Less than timestamp")
    private ZonedDateTime lt;

    @Description("Less than or equal to timestamp")
    private ZonedDateTime lte;

    public ZonedDateTime getEq() {
        return eq;
    }

    public void setEq(ZonedDateTime eq) {
        this.eq = eq;
    }

    public ZonedDateTime getGt() {
        return gt;
    }

    public void setGt(ZonedDateTime gt) {
        this.gt = gt;
    }

    public ZonedDateTime getGte() {
        return gte;
    }

    public void setGte(ZonedDateTime gte) {
        this.gte = gte;
    }

    public ZonedDateTime getLt() {
        return lt;
    }

    public void setLt(ZonedDateTime lt) {
        this.lt = lt;
    }

    public ZonedDateTime getLte() {
        return lte;
    }

    public void setLte(ZonedDateTime lte) {
        this.lte = lte;
    }
}
