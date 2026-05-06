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
package org.kubesmarts.logic.dataindex.model;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

/**
 * Jackson deserializer that extracts enum values from Elasticsearch terms aggregation bucket format.
 *
 * <p>Handles four formats:
 * <ul>
 *   <li>Simple enum string: {@code "RUNNING"} → returns WorkflowInstanceStatus.RUNNING
 *   <li>Terms aggregation: {@code {"buckets": [{"key": "RUNNING", "doc_count": 1}]}} → returns WorkflowInstanceStatus.RUNNING
 *   <li>Nested bucket: {@code {"value": {"RUNNING": 1}}} → returns WorkflowInstanceStatus.RUNNING
 *   <li>Direct bucket: {@code {"RUNNING": 1}} → returns WorkflowInstanceStatus.RUNNING
 * </ul>
 */
public class BucketEnumDeserializer extends JsonDeserializer<Enum<?>> implements ContextualDeserializer {

    private Class<? extends Enum> enumClass;

    public BucketEnumDeserializer() {
    }

    public BucketEnumDeserializer(Class<? extends Enum> enumClass) {
        this.enumClass = enumClass;
    }

    @Override
    public Enum<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);

        if (node == null || node.isNull()) {
            return null;
        }

        String enumValue = null;

        // Simple string value
        if (node.isTextual()) {
            enumValue = node.asText();
        }
        // Bucket format: extract first key
        else if (node.isObject()) {
            // Terms aggregation format: {"buckets": [{"key": "VALUE", "doc_count": N}]}
            if (node.has("buckets")) {
                JsonNode bucketsNode = node.get("buckets");
                if (bucketsNode.isArray() && bucketsNode.size() > 0) {
                    JsonNode firstBucket = bucketsNode.get(0);
                    if (firstBucket.has("key")) {
                        enumValue = firstBucket.get("key").asText();
                    }
                }
            }
            // Check for nested bucket: {"value": {"KEY": count}}
            else if (node.has("value")) {
                JsonNode valueNode = node.get("value");
                if (valueNode.isObject() && valueNode.fields().hasNext()) {
                    enumValue = valueNode.fields().next().getKey();
                }
            }
            // Direct bucket: {"KEY": count}
            else if (node.fields().hasNext()) {
                enumValue = node.fields().next().getKey();
            }
        }

        if (enumValue != null && enumClass != null) {
            return Enum.valueOf(enumClass, enumValue);
        }

        return null;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        Class<?> rawClass = context.getContextualType() != null
            ? context.getContextualType().getRawClass()
            : property.getType().getRawClass();

        if (rawClass.isEnum()) {
            return new BucketEnumDeserializer((Class<? extends Enum>) rawClass);
        }

        return this;
    }
}
