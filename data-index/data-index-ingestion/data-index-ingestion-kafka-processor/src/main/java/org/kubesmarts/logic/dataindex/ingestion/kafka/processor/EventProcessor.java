package org.kubesmarts.logic.dataindex.ingestion.kafka.processor;

public interface EventProcessor<T> {

    void process(T event);
}
