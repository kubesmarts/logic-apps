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

import org.kubesmarts.logic.dataindex.ingestion.persistence.TaskExecution;
import org.kubesmarts.logic.dataindex.model.Error;

final class TaskEventRowMapper {

    private TaskEventRowMapper() {
    }

    static TaskExecution toTaskExecution(TaskEventRow row) {
        return new TaskExecution(
                row.instanceId(),
                row.taskName(),
                row.task(),
                row.status(),
                row.startedAt(),
                row.endedAt(),
                row.input(),
                row.output(),
                toError(row),
                row.eventTimestamp());
    }

    private static Error toError(TaskEventRow row) {
        if (!hasErrorFields(row)) {
            return null;
        }
        Error error = new Error();
        error.setType(row.errorType());
        error.setTitle(row.errorTitle());
        error.setDetail(row.errorDetail());
        error.setStatus(row.errorStatus());
        error.setInstance(row.errorInstance());
        return error;
    }

    private static boolean hasErrorFields(TaskEventRow row) {
        return row.errorType() != null || row.errorTitle() != null || row.errorDetail() != null
                || row.errorStatus() != null || row.errorInstance() != null;
    }
}
