package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.Flow;
import io.quarkiverse.flow.dsl.FlowWorkflowBuilder;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;

import static io.quarkiverse.flow.dsl.FlowDSL.set;

/**
 * Simple hello world workflow using Java DSL.
 */
@ApplicationScoped
public class HelloWorldWorkflow extends Flow {

    @Override
    public Workflow descriptor() {
        return FlowWorkflowBuilder.workflow("hello-world")
                .tasks(
                        set("""
                            {
                              message: "Hello, World!",
                              author: "Quarkus Flow",
                              platform: "Kubernetes"
                            }
                            """)
                )
                .build();
    }
}
