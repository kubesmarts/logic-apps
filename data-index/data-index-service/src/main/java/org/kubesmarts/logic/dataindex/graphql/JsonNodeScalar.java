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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import io.smallrye.graphql.api.AdaptToScalar;
import io.smallrye.graphql.api.Scalar;

/**
 * GraphQL scalar adapter for Jackson JsonNode.
 *
 * Maps JsonNode to GraphQL JSON scalar (represented as String).
 */
public class JsonNodeScalar {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Adapter class that converts JsonNode to/from String for GraphQL.
     * Annotated with @Adapt to tell SmallRye GraphQL how to handle JsonNode.
     */
    @AdaptToScalar(Scalar.String.class)
    public static class JsonNodeAdapter implements Coercing<JsonNode, String> {

        @Override
        public String serialize(Object dataFetcherResult) throws CoercingSerializeException {
            if (dataFetcherResult == null) {
                return null;
            }
            if (dataFetcherResult instanceof JsonNode) {
                return ((JsonNode) dataFetcherResult).toString();
            }
            throw new CoercingSerializeException("Unable to serialize " + dataFetcherResult + " as JsonNode");
        }

        @Override
        public JsonNode parseValue(Object input) throws CoercingParseValueException {
            if (input == null) {
                return null;
            }
            try {
                if (input instanceof String) {
                    return OBJECT_MAPPER.readTree((String) input);
                }
                return OBJECT_MAPPER.convertValue(input, JsonNode.class);
            } catch (JsonProcessingException e) {
                throw new CoercingParseValueException("Unable to parse value " + input + " as JsonNode", e);
            }
        }

        @Override
        public JsonNode parseLiteral(Object input) throws CoercingParseLiteralException {
            if (input == null) {
                return null;
            }
            if (input instanceof StringValue) {
                try {
                    return OBJECT_MAPPER.readTree(((StringValue) input).getValue());
                } catch (JsonProcessingException e) {
                    throw new CoercingParseLiteralException("Unable to parse literal " + input + " as JsonNode", e);
                }
            }
            throw new CoercingParseLiteralException("Unable to parse literal " + input + " as JsonNode");
        }
    }

    /**
     * GraphQL scalar definition for JsonNode.
     * This scalar serializes JsonNode as JSON string.
     */
    public static final GraphQLScalarType JSON_NODE_SCALAR = GraphQLScalarType.newScalar()
            .name("JSON")
            .description("JSON value represented as Jackson JsonNode")
            .coercing(new JsonNodeAdapter())
            .build();
}
