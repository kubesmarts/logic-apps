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

/**
 * Event processing metrics for a specific event processor.
 *
 * <p>Provides statistics about event processing performance and backlog.
 */
public class EventMetrics {

    private String processorName;
    private long backlog;
    private long oldestUnprocessedAgeSeconds;

    public EventMetrics() {
    }

    public EventMetrics(String processorName, long backlog, long oldestUnprocessedAgeSeconds) {
        this.processorName = processorName;
        this.backlog = backlog;
        this.oldestUnprocessedAgeSeconds = oldestUnprocessedAgeSeconds;
    }

    public String getProcessorName() {
        return processorName;
    }

    public void setProcessorName(String processorName) {
        this.processorName = processorName;
    }

    public long getBacklog() {
        return backlog;
    }

    public void setBacklog(long backlog) {
        this.backlog = backlog;
    }

    public long getOldestUnprocessedAgeSeconds() {
        return oldestUnprocessedAgeSeconds;
    }

    public void setOldestUnprocessedAgeSeconds(long oldestUnprocessedAgeSeconds) {
        this.oldestUnprocessedAgeSeconds = oldestUnprocessedAgeSeconds;
    }
}
