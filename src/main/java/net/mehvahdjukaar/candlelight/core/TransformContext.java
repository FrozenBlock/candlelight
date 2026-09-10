package net.mehvahdjukaar.candlelight.core;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.Serializable;

/**
 * The slice of project state the bytecode processors actually need, captured as plain
 * serializable values.
 */
public final class TransformContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String PREFIX = "[CANDLELIGHT] ";
    private static final Logger LOGGER = Logging.getLogger("candlelight");

    private final String projectName;
    private final boolean clientOnly;
    private final boolean serverOnly;
    private final boolean logging;

    private transient boolean headerLogged;

    public TransformContext(String projectName, boolean clientOnly, boolean serverOnly, boolean logging) {
        this.projectName = projectName;
        this.clientOnly = clientOnly;
        this.serverOnly = serverOnly;
        this.logging = logging;
    }

    public String getProjectName() {
        return projectName;
    }

    public boolean isClientOnly() {
        return clientOnly;
    }

    public boolean isServerOnly() {
        return serverOnly;
    }

    public void log(String message) {
        if (logging) {
            LOGGER.lifecycle(PREFIX + message);
        } else {
            LOGGER.info(PREFIX + message);
        }
    }

    public boolean logHeaderOnce() {
        if (!headerLogged) {
            headerLogged = true;
            log("processing annotations");
        }
        return true;
    }

    public boolean hasLoggedHeader() {
        return headerLogged;
    }
}
