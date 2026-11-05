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
import org.kubesmarts.logic.dataindex.model.TaskExecution;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

final class TaskEventRowMapper {

    private TaskEventRowMapper() {
    }

    static TaskExecution toTaskExecution(TaskEventRow row, ObjectMapper objectMapper) {
        TaskExecution execution = new TaskExecution();
        execution.setInstanceId(row.instanceId());
        execution.setTaskName(row.taskName());
        execution.setTask(row.task());
        execution.setStatus(row.status());
        execution.setStartedAt(toZonedDateTime(row.startedAt()));
        execution.setEndedAt(toZonedDateTime(row.endedAt()));
        execution.setInput(toJsonNode(row.input(), objectMapper));
        execution.setOutput(toJsonNode(row.output(), objectMapper));
        execution.setEventTimestamp(toZonedDateTime(row.eventTimestamp()));

        if (hasErrorFields(row)) {
            Error error = new Error();
            error.setType(row.errorType());
            error.setTitle(row.errorTitle());
            error.setDetail(row.errorDetail());
            error.setStatus(row.errorStatus());
            error.setInstance(row.errorInstance());
            execution.setError(error);
        }

        return execution;
    }

    private static boolean hasErrorFields(TaskEventRow row) {
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
