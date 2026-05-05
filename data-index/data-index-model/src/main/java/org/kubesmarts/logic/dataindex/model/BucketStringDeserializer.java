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
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Jackson deserializer that extracts string values from Elasticsearch terms aggregation bucket format.
 *
 * <p>Handles three formats:
 * <ul>
 *   <li>Simple string: {@code "simple-set"} → returns "simple-set"
 *   <li>Nested bucket (filter+terms): {@code {"value": {"simple-set": 1}}} → returns "simple-set"
 *   <li>Direct bucket (terms): {@code {"simple-set": 1}} → returns "simple-set"
 * </ul>
 *
 * <p>Used for fields aggregated by Elasticsearch transforms from raw event indices.
 */
public class BucketStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);

        if (node == null || node.isNull()) {
            return null;
        }

        // Simple string value
        if (node.isTextual()) {
            return node.asText();
        }

        // Bucket format: extract first key
        if (node.isObject()) {
            // Check for nested bucket: {"value": {"key": count}}
            if (node.has("value")) {
                JsonNode valueNode = node.get("value");
                if (valueNode.isObject() && valueNode.fields().hasNext()) {
                    return valueNode.fields().next().getKey();
                }
                return null;
            }

            // Direct bucket: {"key": count}
            if (node.fields().hasNext()) {
                return node.fields().next().getKey();
            }
        }

        return null;
    }
}
