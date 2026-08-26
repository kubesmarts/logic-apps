/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kubesmarts.logic.dataindex.json;

import java.util.Iterator;
import java.util.Map;

import org.kie.kogito.persistence.api.query.AttributeFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON utilities for Data Index.
 *
 * <p><b>Data Index v1.0.0 Changes</b>:
 * <ul>
 *   <li>Removed CloudEvents module (FluentBit handles log ingestion)
 *   <li>Uses Quarkus-managed ObjectMapper instead of static instance
 *   <li>Delegates to {@link ObjectMapperProducer} for consistent configuration
 * </ul>
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    /**
     * Get Quarkus-managed ObjectMapper.
     *
     * @return Quarkus-managed ObjectMapper with proper configuration
     */
    public static ObjectMapper getObjectMapper() {
        return ObjectMapperProducer.get();
    }

    public static ObjectNode mergeVariable(String variableName, Object variableValue, JsonNode variables) {
        return merge(createObjectNode(variableName, variableValue), variables);
    }

    private static ObjectNode createObjectNode(String variableName, Object variableValue) {
        ObjectMapper mapper = getObjectMapper();
        int indexOf = variableName.indexOf('.');
        ObjectNode result = mapper.createObjectNode();
        if (indexOf == -1) {
            result.set(variableName, mapper.valueToTree(variableValue));
        } else {
            String name = variableName.substring(0, indexOf);
            result.set(name, createObjectNode(variableName.substring(indexOf + 1), variableValue));
        }
        return result;
    }

    private static ObjectNode merge(JsonNode update, JsonNode base) {
        if (base == null || base.isNull()) {
            return (ObjectNode) update;
        }
        if (update == null || update.isNull()) {
            return (ObjectNode) base;
        }

        ObjectMapper mapper = getObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        result.setAll((ObjectNode) base);

        Iterator<Map.Entry<String, JsonNode>> iterator = update.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if (result.has(key) && result.get(key).isObject() && value.isObject()) {
                result.set(key, merge(value, result.get(key)));
            } else {
                result.set(key, value);
            }
        }

        return result;
    }

    public static <T> AttributeFilter<T> jsonFilter(AttributeFilter<T> filter) {
        if (filter != null) {
            filter.setJson(true);
        }
        return filter;
    }
}
