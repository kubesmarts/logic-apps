package org.kubesmarts.logic.dataindex.ingestion.kafka.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.jackson.JsonFormat;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KafkaIngestionObjectMapperCustomizer implements ObjectMapperCustomizer {
    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        objectMapper.registerModule(new JavaTimeModule());
    }
}
