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

import org.kie.kogito.persistence.api.query.AttributeFilter;
import org.kie.kogito.persistence.api.query.FilterCondition;

/**
 * Public wrapper for Kogito's AttributeFilter (which has protected constructor).
 *
 * <p>Enables creating AttributeFilter instances from GraphQL filter converters.
 *
 * @param <T> Type of filter value
 */
public class DataIndexAttributeFilter<T> extends AttributeFilter<T> {

    /**
     * Create a new attribute filter.
     *
     * @param attribute Field name (supports dot-notation for nested paths)
     * @param condition Filter condition (EQUAL, LIKE, IN, GT, etc.)
     * @param value Filter value
     */
    public DataIndexAttributeFilter(String attribute, FilterCondition condition, T value) {
        super(attribute, condition, value);
    }
}
