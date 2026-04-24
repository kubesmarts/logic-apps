package org.kubesmarts.logic.dataindex.test;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.fluent.func.FuncWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.set;

/**
 * Simple hello world workflow using Java DSL.
 */
@ApplicationScoped
public class HelloWorldWorkflow extends Flow {

    @Override
    public Workflow descriptor() {
        return FuncWorkflowBuilder.workflow("hello-world")
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
