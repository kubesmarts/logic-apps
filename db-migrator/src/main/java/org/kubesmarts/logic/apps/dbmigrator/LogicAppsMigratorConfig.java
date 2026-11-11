package org.kubesmarts.logic.apps.dbmigrator;

import io.smallrye.config.ConfigMapping;

import java.util.List;

@ConfigMapping(prefix = "logic.apps.migrator")
public interface LogicAppsMigratorConfig {

    /**
     * File where the JSON {@link MigrationReport} is written after the run. In Kubernetes this is the
     * container termination-message path ({@code /dev/termination-log}), so logic-operator can read the
     * result from the Pod status.
     */
    String terminationLogPath();

    /**
     * Migration streams to apply, in order: {@code runtime}, {@code quartz}, {@code data-index}.
     * Set by logic-operator as a comma-separated list.
     */
    List<MigrationStreams> include();

}
