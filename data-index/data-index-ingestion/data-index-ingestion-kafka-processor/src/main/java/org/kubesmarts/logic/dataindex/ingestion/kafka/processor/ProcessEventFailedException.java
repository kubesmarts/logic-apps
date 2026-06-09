package org.kubesmarts.logic.dataindex.ingestion.kafka.processor;

public class ProcessEventFailedException extends RuntimeException {

    public ProcessEventFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
