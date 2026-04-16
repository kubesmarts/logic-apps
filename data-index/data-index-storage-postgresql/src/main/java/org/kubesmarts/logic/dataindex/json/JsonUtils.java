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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON utilities for Data Index.
 *
 * Data Index v1.0.0: Removed CloudEvents module registration as Data Index
 * no longer processes CloudEvents (FluentBit handles log ingestion).
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = configure(new ObjectMapper());

    private JsonUtils() {
    }

    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }

    public static ObjectMapper configure(ObjectMapper objectMapper) {
        return objectMapper
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule())
                .findAndRegisterModules();
    }

    public static ObjectNode mergeVariable(String variableName, Object variableValue, JsonNode variables) {
        return merge(createObjectNode(variableName, variableValue), variables);
    }

    private static ObjectNode createObjectNode(String variableName, Object variableValue) {
        int indexOf = variableName.indexOf('.');
        ObjectNode result = MAPPER.createObjectNode();
        if (indexOf == -1) {
            result.set(variableName, MAPPER.valueToTree(variableValue));
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

        ObjectNode result = MAPPER.createObjectNode();
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
