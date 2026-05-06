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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Custom Jackson deserializer for ZonedDateTime that treats numeric values as epoch milliseconds.
 *
 * <p>Elasticsearch returns date fields as either:
 * <ul>
 *   <li>ISO-8601 strings: "2026-05-06T17:30:00Z"
 *   <li>Epoch milliseconds: 1778087851500
 * </ul>
 *
 * <p>Jackson's default ZonedDateTime deserializer treats numbers as epoch <b>seconds</b>, not milliseconds.
 * This causes dates like 1778087851500 (May 2026) to be interpreted as year 58315.
 *
 * <p>This deserializer fixes that by:
 * <ul>
 *   <li>String values: Parse as ISO-8601 (standard behavior)
 *   <li>Numeric values: Interpret as epoch <b>milliseconds</b> (not seconds)
 * </ul>
 */
public class EpochMillisZonedDateTimeDeserializer extends JsonDeserializer<ZonedDateTime> {

    @Override
    public ZonedDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isNull()) {
            return null;
        }

        if (node.isTextual()) {
            return ZonedDateTime.parse(node.asText());
        }

        if (node.isNumber()) {
            long epochMillis = node.asLong();
            return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
        }

        throw new IllegalArgumentException("Cannot deserialize ZonedDateTime from: " + node);
    }
}
