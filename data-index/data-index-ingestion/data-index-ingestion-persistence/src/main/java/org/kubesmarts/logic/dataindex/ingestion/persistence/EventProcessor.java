package org.kubesmarts.logic.dataindex.ingestion.persistence;

/**
 * Persists lifecycle events into the normalized tables.
 *
 * @param <T> the event type, {@link WorkflowInstance} or {@link TaskExecution}
 */
public interface EventProcessor<T> {

    /**
     * Persists a single event. Safe to replay and to call out of order: the stored result is the same.
     *
     * @throws ProcessEventFailedException if the event cannot be persisted
     */
    void process(T event);
}
