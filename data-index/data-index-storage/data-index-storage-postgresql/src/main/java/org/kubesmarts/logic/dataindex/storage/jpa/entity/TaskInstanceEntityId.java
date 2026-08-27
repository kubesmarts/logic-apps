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
package org.kubesmarts.logic.dataindex.storage.jpa.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite ID class for TaskInstanceEntity.
 *
 * <p>Represents the composite primary key (instance_id, task).
 * Uses @Embeddable approach for better JPA handling of composite keys with foreign keys.
 *
 * <p><b>Open Workflow Alignment:</b> Column 'task' stores JSON Pointer (e.g., '/do/1/initialize')
 * matching the Open Workflow specification's 'task' field in lifecycle events.
 */
@Embeddable
public class TaskInstanceEntityId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "instance_id")
    private String instanceId;

    @Column(name = "task")
    private String task;

    public TaskInstanceEntityId() {
    }

    public TaskInstanceEntityId(String instanceId, String task) {
        this.instanceId = instanceId;
        this.task = task;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskInstanceEntityId that = (TaskInstanceEntityId) o;
        return Objects.equals(instanceId, that.instanceId) &&
               Objects.equals(task, that.task);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, task);
    }
}
