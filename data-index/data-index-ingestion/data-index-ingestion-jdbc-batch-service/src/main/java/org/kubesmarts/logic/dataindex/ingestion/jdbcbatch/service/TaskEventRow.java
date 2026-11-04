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
package org.kubesmarts.logic.dataindex.ingestion.jdbcbatch.service;

import java.time.OffsetDateTime;

record TaskEventRow(
        long id,
        String instanceId,
        String taskName,
        String task,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String input,
        String output,
        String errorType,
        String errorTitle,
        String errorDetail,
        Integer errorStatus,
        String errorInstance,
        OffsetDateTime eventTimestamp) {
}
