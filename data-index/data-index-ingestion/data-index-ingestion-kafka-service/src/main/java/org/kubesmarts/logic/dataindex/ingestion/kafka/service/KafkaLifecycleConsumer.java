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
import io.cloudevents.CloudEventData;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.BytesCloudEventData;
import io.cloudevents.core.data.PojoCloudEventData;
import io.cloudevents.jackson.JsonCloudEventData;
import org.apache.kafka.common.header.Header;
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

/**
 * High-throughput Kafka consumer for Quarkus Flow lifecycle events.
 *
 * <p><b>Architecture:</b> Batch consumption with manual CloudEvent reconstruction.
 *
 * <p><b>Why batch mode?</b>
 * - Processes up to 1000 Kafka records per poll (configurable)
 * - DB writes in batches (reduces transaction overhead)
 * - Critical for high-volume workflow environments (multiple concurrent workflows)
 *
 * <p><b>Why manual CloudEvent reconstruction?</b>
 * - SmallRye's automatic CloudEvent extraction (via {@code CloudEventMetadata}) only works in per-message mode
 * - Batch mode requires manual extraction from Kafka headers (binary) or JSON (structured)
 * - Trade-off: Higher throughput vs manual CE handling (acceptable for high-volume environments)
 *
 * <p><b>CloudEvents Support:</b>
 * <ul>
 *   <li><b>Binary mode</b> (default): CE attributes in Kafka headers ({@code ce_type}, {@code ce_time}, etc.), data in body</li>
 *   <li><b>Structured mode</b>: Full CloudEvent JSON envelope in message body</li>
 *   <li>Auto-detection per record (checks for {@code ce_type} header presence)</li>
 * </ul>
 *
 * <p><b>Performance:</b>
 * - Kafka batch size: SmallRye default (max.poll.records)
 * - DB batch size: {@code data-index.ingestion.db-batch-size} (default: 1000)
 * - CloudEvent reconstruction overhead: negligible compared to DB operations
 */
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

                CloudEventData cloudEventData = cloudEvent.getData();
                if (cloudEventData == null) {
                    throw new IllegalArgumentException("The CloudEvent data consumed at offset %s from partition %s is null or empty."
                            .formatted(record.offset(), record.partition()));
                }

                Class<?> eventClass = LifecycleEventUtils.getEventClass(cloudEvent.getType());
                Object data;

                // Handle both binary mode (BytesCloudEventData) and structured mode (JsonCloudEventData)
                if (cloudEventData instanceof BytesCloudEventData bytesData) {
                    // Binary mode: data is raw bytes of JSON payload
                    String dataJson = new String(bytesData.toBytes(), StandardCharsets.UTF_8);
                    data = jackson.readValue(dataJson, eventClass);
                } else if (cloudEventData instanceof JsonCloudEventData jsonData) {
                    // Structured mode: data is already parsed as JsonNode
                    if (jsonData.getNode().isTextual()) {
                        // Data is a JSON string, parse it first
                        String dataJson = jsonData.getNode().asText();
                        data = jackson.readValue(dataJson, eventClass);
                    } else {
                        // Data is already a parsed JSON object
                        data = jackson.convertValue(jsonData.getNode(), eventClass);
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported CloudEventData type: " + cloudEventData.getClass().getName());
                }

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

    /**
     * Validates and reconstructs a CloudEvent from a Kafka record.
     *
     * <p>Supports both CloudEvents content modes:
     * <ul>
     *   <li><b>Binary mode</b> (default): CE attributes in Kafka headers, data in message body</li>
     *   <li><b>Structured mode</b>: Full CloudEvent JSON envelope in message body</li>
     * </ul>
     *
     * <p><b>Why manual reconstruction?</b>
     * <ul>
     *   <li>Batch mode ({@code Message<ConsumerRecords<...>>}) bypasses SmallRye's automatic CE extraction</li>
     *   <li>SmallRye's {@code CloudEventMetadata} only works in per-message mode</li>
     *   <li>Manual reconstruction provides: CloudEvents spec validation, uniform abstraction, future-proof field extraction</li>
     *   <li>Currently used fields: {@code time} (→ event_timestamp), {@code type} (→ event routing)</li>
     *   <li>Reconstruction overhead is negligible compared to DB UPSERT operations</li>
     * </ul>
     *
     * @param record Kafka consumer record
     * @return Validated CloudEvent object
     * @throws JsonProcessingException if JSON parsing fails
     * @throws IllegalArgumentException if CloudEvent is invalid (missing type/time)
     */
    private CloudEvent validateCloudEvent(ConsumerRecord<String, String> record) throws JsonProcessingException {
        if (record.value() == null || record.value().isEmpty()) {
            throw new IllegalArgumentException("Event record consumed at offset %s, from partition %s is null or empty."
                    .formatted(record.topic(), record.partition()));
        }

        CloudEvent cloudEvent;

        // Auto-detect CloudEvents mode: binary (headers) vs structured (JSON envelope)
        if (isBinaryCloudEvent(record)) {
            cloudEvent = buildCloudEventFromBinaryMode(record);
        } else {
            // Structured mode: full CloudEvent JSON in message body
            cloudEvent = jackson.readValue(record.value(), CloudEvent.class);
        }

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

    /**
     * Detects binary CloudEvents mode by checking for {@code ce_type} header.
     *
     * @param record Kafka consumer record
     * @return true if binary mode (CE attributes in headers), false if structured mode (JSON envelope)
     */
    private boolean isBinaryCloudEvent(ConsumerRecord<String, String> record) {
        // Binary mode has CE attributes in Kafka headers (ce_type, ce_source, etc.)
        Header typeHeader = record.headers().lastHeader("ce_type");
        return typeHeader != null;
    }

    /**
     * Reconstructs a CloudEvent object from binary mode (CE attributes in Kafka headers).
     *
     * <p>Quarkus Flow default mode: {@code mp.messaging.outgoing.flow-lifecycle-out.cloud-events=false}
     *
     * @param record Kafka consumer record with CloudEvent attributes in headers
     * @return CloudEvent object
     * @throws JsonProcessingException if data payload JSON parsing fails
     * @throws IllegalArgumentException if required headers are missing
     */
    private CloudEvent buildCloudEventFromBinaryMode(ConsumerRecord<String, String> record) throws JsonProcessingException {
        // Extract CE attributes from Kafka headers
        String type = getHeaderValue(record, "ce_type");
        String source = getHeaderValue(record, "ce_source");
        String id = getHeaderValue(record, "ce_id");
        String time = getHeaderValue(record, "ce_time");

        if (type == null || source == null || id == null) {
            throw new IllegalArgumentException("Binary CloudEvent missing required headers (ce_type, ce_source, or ce_id)");
        }

        // Data payload is in the message value (JSON string of lifecycle event)
        String dataJson = record.value();

        // Build CloudEvent with data as JsonCloudEventData
        CloudEventBuilder builder = CloudEventBuilder.v1()
                .withType(type)
                .withSource(java.net.URI.create(source))
                .withId(id)
                .withData("application/json", bytes(dataJson));

        if (time != null) {
            builder.withTime(java.time.OffsetDateTime.parse(time));
        }

        return builder.build();
    }

    private String getHeaderValue(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
