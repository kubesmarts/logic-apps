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
package org.kubesmarts.logic.dataindex.service.postgresql.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kubesmarts.logic.dataindex.model.Error;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

final class WorkflowEventRowMapper {

    private WorkflowEventRowMapper() {
    }

    static WorkflowInstance toWorkflowInstance(WorkflowEventRow row, ObjectMapper objectMapper) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(row.instanceId());
        instance.setNamespace(row.namespace());
        instance.setName(row.name());
        instance.setVersion(row.version());
        if (row.status() != null) {
            instance.setStatus(WorkflowInstanceStatus.valueOf(row.status()));
        }
        instance.setStartedAt(toZonedDateTime(row.startedAt()));
        instance.setEndedAt(toZonedDateTime(row.endedAt()));
        instance.setLastUpdate(toZonedDateTime(row.lastUpdate()));
        instance.setInput(toJsonNode(row.input(), objectMapper));
        instance.setOutput(toJsonNode(row.output(), objectMapper));
        instance.setEventTimestamp(toZonedDateTime(row.eventTimestamp()));

        if (hasErrorFields(row)) {
            Error error = new Error();
            error.setType(row.errorType());
            error.setTitle(row.errorTitle());
            error.setDetail(row.errorDetail());
            error.setStatus(row.errorStatus());
            error.setInstance(row.errorInstance());
            instance.setError(error);
        }

        return instance;
    }

    private static boolean hasErrorFields(WorkflowEventRow row) {
        return row.errorType() != null || row.errorTitle() != null || row.errorDetail() != null
                || row.errorStatus() != null || row.errorInstance() != null;
    }

    private static ZonedDateTime toZonedDateTime(OffsetDateTime value) {
        return value != null ? value.toZonedDateTime() : null;
    }

    private static JsonNode toJsonNode(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse staging column as JSON: " + json, e);
        }
    }
}
