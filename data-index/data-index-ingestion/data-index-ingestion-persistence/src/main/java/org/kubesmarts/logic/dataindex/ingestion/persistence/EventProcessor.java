package org.kubesmarts.logic.dataindex.ingestion.persistence;

public interface EventProcessor<T> {

    void process(T event);
}
