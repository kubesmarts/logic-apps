package org.kubesmarts.logic.apps.dbmigrator;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MigrationReport(List<StreamResult> streams, String error) {
}
