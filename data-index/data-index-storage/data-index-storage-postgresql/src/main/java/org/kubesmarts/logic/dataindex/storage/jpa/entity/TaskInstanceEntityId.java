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
 * <p>Represents the composite primary key (instance_id, task_position).
 * Uses @Embeddable approach for better JPA handling of composite keys with foreign keys.
 */
@Embeddable
public class TaskInstanceEntityId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "instance_id")
    private String instanceId;

    @Column(name = "task_position")
    private String taskPosition;

    public TaskInstanceEntityId() {
    }

    public TaskInstanceEntityId(String instanceId, String taskPosition) {
        this.instanceId = instanceId;
        this.taskPosition = taskPosition;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getTaskPosition() {
        return taskPosition;
    }

    public void setTaskPosition(String taskPosition) {
        this.taskPosition = taskPosition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskInstanceEntityId that = (TaskInstanceEntityId) o;
        return Objects.equals(instanceId, that.instanceId) &&
               Objects.equals(taskPosition, that.taskPosition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId, taskPosition);
    }
}
