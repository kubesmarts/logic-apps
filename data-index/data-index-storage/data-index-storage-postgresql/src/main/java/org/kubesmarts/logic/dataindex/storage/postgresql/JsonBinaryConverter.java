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
package org.kubesmarts.logic.dataindex.postgresql;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.kubesmarts.logic.dataindex.json.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.AttributeConverter;

/**
 * Data Index v1.0.0: Uses JsonUtils.getObjectMapper() instead of kogito-jackson-utils.
 */
public class JsonBinaryConverter implements AttributeConverter<JsonNode, String> {

    @Override
    public String convertToDatabaseColumn(JsonNode attribute) {
        try {
            return attribute == null ? null : JsonUtils.getObjectMapper().writeValueAsString(attribute);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public JsonNode convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : JsonUtils.getObjectMapper().readValue(dbData, JsonNode.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
