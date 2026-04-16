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

/**
 * Workflow instance execution status for Serverless Workflow 1.0.0.
 *
 * <p>Status values map directly to Quarkus Flow event types:
 * <ul>
 *   <li>RUNNING - Instance executing (from workflow.instance.started)
 *   <li>COMPLETED - Instance completed successfully (from workflow.instance.completed)
 *   <li>FAULTED - Instance encountered an error (from workflow.instance.faulted)
 *   <li>CANCELLED - Instance was cancelled (from workflow.instance.cancelled)
 *   <li>SUSPENDED - Instance execution paused (from workflow.instance.suspended)
 * </ul>
 *
 * <p><b>Note</b>: v0.8 used integer state codes (PENDING=0, ACTIVE=1, COMPLETED=2, ABORTED=3, SUSPENDED=4, ERROR=5).
 * v1.0.0 uses string status values aligned with SW 1.0.0 spec.
 */
public enum WorkflowInstanceStatus {
    RUNNING,
    COMPLETED,
    FAULTED,
    CANCELLED,
    SUSPENDED;

    /**
     * Convert v0.8 integer state code to v1.0.0 status enum.
     * Used for backwards compatibility when migrating from v0.8 data.
     *
     * @param state integer state code (0=PENDING/RUNNING, 1=ACTIVE/RUNNING, 2=COMPLETED, 3=ABORTED/CANCELLED, 4=SUSPENDED, 5=ERROR/FAULTED)
     * @return corresponding v1.0.0 status enum
     */
    public static WorkflowInstanceStatus fromV08State(Integer state) {
        if (state == null) {
            return null;
        }
        switch (state) {
            case 0: // PENDING
            case 1: // ACTIVE
                return RUNNING;
            case 2: // COMPLETED
                return COMPLETED;
            case 3: // ABORTED
                return CANCELLED;
            case 4: // SUSPENDED
                return SUSPENDED;
            case 5: // ERROR
                return FAULTED;
            default:
                return null;
        }
    }
}
