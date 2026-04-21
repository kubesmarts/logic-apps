package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.annotations.FlowId;
import io.serverlessworkflow.api.Workflow;
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.actions.SetAction;
import io.serverlessworkflow.api.functions.FunctionDefinition;
import io.serverlessworkflow.api.types.Metadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Simple workflow defined using Java DSL.
 * Sets some context variables and completes.
 */
@ApplicationScoped
public class SimpleSetWorkflow {

    @Produces
    @FlowId("test:simple-set-java")
    public Workflow simpleSet() {
        return Workflow.builder()
                .withDocument(doc -> doc
                        .withDsl("1.0.0")
                        .withNamespace("test")
                        .withName("simple-set-java")
                        .withVersion("1.0.0"))
                .withDo(
                        // Task 1: Set greeting
                        Action.builder()
                                .withSet(SetAction.builder()
                                        .withSet(Map.of(
                                                "greeting", "Hello from Java DSL!",
                                                "timestamp", OffsetDateTime.now().toString(),
                                                "message", "Quarkus Flow structured logging test"
                                        ))
                                        .build())
                                .build(),
                        // Task 2: Set completion flag
                        Action.builder()
                                .withSet(SetAction.builder()
                                        .withSet(Map.of(
                                                "completed", true,
                                                "mode", "Mode 1: PostgreSQL Polling"
                                        ))
                                        .build())
                                .build()
                )
                .build();
    }
}
