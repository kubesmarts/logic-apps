package org.kubesmarts.logic.apps.dbmigrator;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Written as JSON to the container's termination message on both success and failure.
 * Field names/shape must match logic-operator's migrationReport (internal/controller/
 * migration_report.go) exactly. There is no top-level "success" field - the operator
 * determines success from the Job/Pod exit code, not from this report's content. Only
 * successfully-applied streams appear in "streams"; "error" names whichever failed.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MigrationReport(List<StreamResult> streams, String error) {
}
