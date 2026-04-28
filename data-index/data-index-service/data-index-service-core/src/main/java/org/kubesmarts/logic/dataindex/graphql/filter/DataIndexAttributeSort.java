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

import org.kie.kogito.persistence.api.query.AttributeSort;
import org.kie.kogito.persistence.api.query.SortDirection;

/**
 * Public wrapper for Kogito's AttributeSort (which has protected constructor).
 *
 * <p>Enables creating AttributeSort instances from GraphQL orderBy converters.
 */
public class DataIndexAttributeSort extends AttributeSort {

    /**
     * Create a new attribute sort.
     *
     * @param attribute Field name (supports dot-notation for nested paths)
     * @param sort Sort direction (ASC or DESC)
     */
    public DataIndexAttributeSort(String attribute, SortDirection sort) {
        super(attribute, sort);
    }
}
