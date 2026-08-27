package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.Workflow;
import io.quarkiverse.flow.dsl.FlowWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import static io.quarkiverse.flow.dsl.FlowDSL.set;

/**
 * Simple workflow defined using Java DSL.
 * Sets some context variables and completes.
 */
@ApplicationScoped
public class SimpleSetWorkflow extends Flow {

    @Override
    public Workflow descriptor() {
        return FlowWorkflowBuilder.workflow("simple-set")
                .tasks(
                        // Task 1: Set greeting and metadata
                        set("""
                            {
                              greeting: "Hello from Java DSL!",
                              timestamp: now(),
                              message: "Quarkus Flow structured logging test"
                            }
                            """),
                        // Task 2: Set completion flag
                        set("""
                            {
                              completed: true,
                              mode: "Mode 1: PostgreSQL Polling"
                            }
                            """)
                )
                .build();
    }
}
