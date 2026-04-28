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
 * Sort direction for GraphQL queries.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * orderBy: {
 *   startTime: DESC
 *   name: ASC
 * }
 * </pre>
 */
@Description("Sort direction")
public enum OrderBy {
    @Description("Ascending order")
    ASC,

    @Description("Descending order")
    DESC
}
