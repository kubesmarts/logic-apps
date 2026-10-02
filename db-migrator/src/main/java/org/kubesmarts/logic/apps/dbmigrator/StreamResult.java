package org.kubesmarts.logic.apps.dbmigrator;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamResult(String name, String appliedVersion, int appliedCount) {
}
