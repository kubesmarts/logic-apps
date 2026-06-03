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
package org.kubesmarts.logic.dataindex.ingestion.kafka.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.jackson.JsonCloudEventData;
import io.serverlessworkflow.impl.lifecycle.ce.TaskCEData;
import io.serverlessworkflow.impl.lifecycle.ce.WorkflowCEData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.EventProcessor;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.ProcessEventFailedException;
import org.kubesmarts.logic.dataindex.model.LifecycleEventUtils;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class KafkaLifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaLifecycleConsumer.class);

    @Inject
    ObjectMapper jackson;

    @Inject
    EventProcessor<WorkflowInstance> workflowEventProcessor;

    @Inject
    EventProcessor<TaskExecution> taskExecutionProcessor;

    @Incoming("data-index-events")
    public void consumeLifecycleEvent(ConsumerRecord<String, String> record) {

        try {
            CloudEvent cloudEvent = validateCloudEvent(record);

            JsonCloudEventData cloudEventData = (JsonCloudEventData) cloudEvent.getData();
            if (cloudEventData == null || cloudEventData.getNode() == null) {
                throw new IllegalArgumentException("The CloudEvent data node consumed at offset %s from partition %s is null or empty."
                        .formatted(record.offset(), record.partition()));
            }

            Class<?> eventClass = LifecycleEventUtils.getEventClass(cloudEvent.getType());
            Object data = jackson.convertValue(cloudEventData.getNode(), eventClass);

            if (data instanceof TaskCEData taskData) {
                handleTaskEvent(cloudEvent, taskData);
            } else if (data instanceof WorkflowCEData workflowData) {
                handleWorkflowEvent(cloudEvent, workflowData);
            } else {
                throw new IllegalArgumentException("Unsupported event type '%s' consumed at offset %s from partition %s."
                        .formatted(cloudEvent.getType(), record.offset(), record.partition()));
            }
        } catch (Exception e) {
            log.error("Failed to consume the record from Kafka at offset '{}' from partition '{}'.", record.offset(), record.partition(), e);
            throw new ProcessEventFailedException("Failed to consume Kafka record at offset %s from partition %s".formatted(
                    record.offset(), record.partition()), e);
        }
    }

    private CloudEvent validateCloudEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        if (record.value() == null || record.value().isEmpty()) {
            throw new IllegalArgumentException("Event record consumed at offset %s, from partition %s is null or empty."
                    .formatted(record.topic(), record.partition()));
        }
        CloudEvent cloudEvent = jackson.readValue(record.value(), CloudEvent.class);
        if (cloudEvent == null || cloudEvent.getType() == null) {
            log.error("The CloudEvent consumed at offset '{}', from partition '{}' is null or has a null type.", record.offset(), record.partition());
            throw new IllegalArgumentException("CloudEvent type is null or empty.");
        }

        if (cloudEvent.getTime() == null) {
            log.error("The CloudEvent's time at offset '{}', from partition '{}' is null.", record.offset(), record.partition());
            throw new IllegalArgumentException("CloudEvent time is null.");
        }
        return cloudEvent;
    }

    private void handleWorkflowEvent(CloudEvent cloudEvent, WorkflowCEData data) {
        try {
            WorkflowInstance workflow = Mapper.mapWorkflowInstanceEvent(cloudEvent, data, jackson);
            workflowEventProcessor.process(workflow);
        } catch (Exception e) {
            log.error("Error while processing CloudEvent (workflow) with ID: {}", data.getName(), e);
            throw new ProcessEventFailedException("Failed to process CloudEvent with ID: " + data.getName(), e);
        }
    }

    private void handleTaskEvent(CloudEvent cloudEvent, TaskCEData data) {
        try {
            TaskExecution taskExecution = Mapper.mapTaskExecutionEvent(cloudEvent, data, jackson);
            taskExecutionProcessor.process(taskExecution);
        } catch (Exception e) {
            log.error("Error while processing CloudEvent (task) with ID: {}", cloudEvent.getId(), e);
            throw new ProcessEventFailedException("Failed to process CloudEvent with ID: " + cloudEvent.getId(), e);
        }
    }
}
