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
package org.kubesmarts.logic.dataindex.metrics;

import java.util.HashMap;
import java.util.Map;

/**
 * Backend-agnostic event processor metrics response.
 *
 * <p>Provides comprehensive metrics for all registered event processors.
 *
 * <p><b>Response Example</b>:
 * <pre>
 * {
 *   "processors": {
 *     "workflow": {
 *       "processorName": "workflow",
 *       "backlog": 0,
 *       "oldestUnprocessedAgeSeconds": 0
 *     },
 *     "task": {
 *       "processorName": "task",
 *       "backlog": 5,
 *       "oldestUnprocessedAgeSeconds": 12
 *     }
 *   },
 *   "totalBacklog": 5,
 *   "maxLagSeconds": 12
 * }
 * </pre>
 */
public class EventProcessorMetricsResponse {

    private Map<String, EventMetrics> processors = new HashMap<>();
    private long totalBacklog;
    private long maxLagSeconds;

    public Map<String, EventMetrics> getProcessors() {
        return processors;
    }

    public void setProcessors(Map<String, EventMetrics> processors) {
        this.processors = processors;
    }

    public void addProcessor(String name, EventMetrics metrics) {
        this.processors.put(name, metrics);
    }

    public long getTotalBacklog() {
        return totalBacklog;
    }

    public void setTotalBacklog(long totalBacklog) {
        this.totalBacklog = totalBacklog;
    }

    public long getMaxLagSeconds() {
        return maxLagSeconds;
    }

    public void setMaxLagSeconds(long maxLagSeconds) {
        this.maxLagSeconds = maxLagSeconds;
    }
}
