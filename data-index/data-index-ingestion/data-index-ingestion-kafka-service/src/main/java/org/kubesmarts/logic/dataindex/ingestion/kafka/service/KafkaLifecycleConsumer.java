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
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.EventProcessor;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.ProcessEventFailedException;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.WorkflowEventProcessor;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.TaskExecutionProcessor;

import org.kubesmarts.logic.dataindex.model.LifecycleEventUtils;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;


@ApplicationScoped
public class KafkaLifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaLifecycleConsumer.class);

    private final WorkflowEventProcessor workflowEventProcessor;
    private final TaskExecutionProcessor taskExecutionProcessor;

    @Inject
    public KafkaLifecycleConsumer(WorkflowEventProcessor workflowEventProcessor,
                                  TaskExecutionProcessor taskExecutionProcessor)
    {
        this.workflowEventProcessor = workflowEventProcessor;
        this.taskExecutionProcessor = taskExecutionProcessor;
    }
    
    @Inject
    ObjectMapper jackson;

    @Inject
    @Channel("data-index-events-dlq")
    MutinyEmitter<String> deadLetterEmitter;

    @ConfigProperty(name = "data-index.ingestion.db-batch-size", defaultValue = "1000")
    int dbBatchSize;

    @Incoming("data-index-events")
    public CompletionStage<Void> consumeLifecycleEvent(Message<ConsumerRecords<String, String>> records) {
        List<CompletionStage<Void>> deadLetterSends = new ArrayList<>();
    
        List<WorkflowInstance> workflowInstances = new ArrayList<>();
        List<TaskExecution> taskExecutions = new ArrayList<>();
    
        for (ConsumerRecord<String, String> record : records.getPayload()) {
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
                    taskExecutions.add(mapTaskEvent(cloudEvent, taskData));
                } else if (data instanceof WorkflowCEData workflowData) {
                    workflowInstances.add(mapWorkflowEvent(cloudEvent, workflowData));
                }
            } catch (Exception e) {
                log.error("Failed to consume the record from Kafka at offset '{}' from partition '{}'. Routing to dead-letter queue.",
                        record.offset(), record.partition(), e);
                deadLetterSends.add(sendToDeadLetterQueue(record, e));
            }
        }
    
        try {
            log.info("Mapped Kafka batch: workflows={}, tasks={}",
                    workflowInstances.size(), taskExecutions.size());
    
            for (List<WorkflowInstance> chunk : partition(workflowInstances, dbBatchSize)) {
                workflowEventProcessor.processBatch(chunk);
            }
    
            for (List<TaskExecution> chunk : partition(taskExecutions, dbBatchSize)) {
                taskExecutionProcessor.processBatch(chunk);
            }
        } catch (Exception e) {
            log.error("Failed to persist Kafka batch. Nacking the batch so it can be retried.", e);
            return records.nack(e);
        }
    
        CompletableFuture<?>[] pending = deadLetterSends.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
    
        return CompletableFuture.allOf(pending).thenCompose(ignored -> records.ack());
    }
        
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
    
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
    
        return chunks;
    }
    
    private TaskExecution mapTaskEvent(CloudEvent cloudEvent, TaskCEData data) {
        try {
            return Mapper.mapTaskExecutionEvent(cloudEvent, data, jackson);
        } catch (Exception e) {
            log.error("Error while mapping CloudEvent (task) with ID: {}", cloudEvent.getId(), e);
            throw new ProcessEventFailedException("Failed to map CloudEvent with ID: " + cloudEvent.getId(), e);
        }
    }
    
    private WorkflowInstance mapWorkflowEvent(CloudEvent cloudEvent, WorkflowCEData data) {
        try {
            return Mapper.mapWorkflowInstanceEvent(cloudEvent, data, jackson);
        } catch (Exception e) {
            log.error("Error while mapping CloudEvent (workflow) with ID: {}", data.getName(), e);
            throw new ProcessEventFailedException("Failed to map CloudEvent with ID: " + data.getName(), e);
        }
    }

    private CompletionStage<Void> sendToDeadLetterQueue(ConsumerRecord<String, String> record, Exception cause) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("dead-letter-reason", bytes(cause.getMessage() != null ? cause.getMessage() : cause.toString()));
        headers.add("dead-letter-cause", bytes(cause.getClass().getName()));
        headers.add("dead-letter-original-topic", bytes(record.topic()));
        headers.add("dead-letter-original-partition", bytes(Integer.toString(record.partition())));
        headers.add("dead-letter-original-offset", bytes(Long.toString(record.offset())));

        OutgoingKafkaRecordMetadata<String> metadata = OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(record.key())
                .withHeaders(headers)
                .build();

        return deadLetterEmitter.sendMessage(Message.of(record.value(), Metadata.of(metadata)))
                .subscribeAsCompletionStage();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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
