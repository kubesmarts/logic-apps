package org.kubesmarts.logic.apps.dbmigrator;

public enum MigrationStreams {
    RUNTIME("runtime"),
    QUARTZ("quartz"),
    DATA_INDEX("data-index");

    private final String stream;

    MigrationStreams(String stream) {
        this.stream = stream;
    }

    @Override
    public String toString() {
        return this.stream;
    }
}
