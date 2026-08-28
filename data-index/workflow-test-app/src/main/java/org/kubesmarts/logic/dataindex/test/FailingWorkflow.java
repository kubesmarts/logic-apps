package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.Workflow;
import io.quarkiverse.flow.dsl.FlowWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import static io.quarkiverse.flow.dsl.FlowDSL.set;

/**
 * Workflow that intentionally fails for testing error handling.
 * Uses a JQ expression that will throw an error when executed.
 */
@ApplicationScoped
public class FailingWorkflow extends Flow {

    @Override
    public Workflow descriptor() {
        return FlowWorkflowBuilder.workflow("failing-workflow")
                .tasks(
                        set("""
                            {
                              message: "About to fail..."
                            }
                            """),
                        // This will cause a division by zero error
                        set(".result = (1 / 0)")
                )
                .build();
    }
}
