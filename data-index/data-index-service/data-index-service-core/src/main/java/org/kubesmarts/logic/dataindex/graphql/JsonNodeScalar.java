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
package org.kubesmarts.logic.dataindex.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Adapter for Jackson JsonNode to JSON string in GraphQL.
 *
 * <p>SmallRye GraphQL will automatically handle JsonNode serialization.
 * This class provides helper methods if needed for manual conversion.
 */
public class JsonNodeScalar {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Convert JsonNode to Object (Map/List/etc.) for GraphQL serialization.
     */
    public static Object toGraphQL(JsonNode node) {
        if (node == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(node, Object.class);
    }

    /**
     * Convert Object back to JsonNode for deserialization.
     */
    public static JsonNode fromGraphQL(Object obj) {
        if (obj == null) {
            return null;
        }
        return OBJECT_MAPPER.valueToTree(obj);
    }
}
