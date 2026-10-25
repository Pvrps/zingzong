package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import java.util.List;

/**
 * What the page needs from whoever owns the servers: the state to show in the state bar, and
 * starting and stopping a recording on every server. The viewer knows nothing else about
 * domains, JVMs or Maven.
 */
public interface DomainControl {

    /** The current state, re-evaluated on every call. */
    ControlState state();

    /**
     * Starts one recording on every server and returns its id.
     *
     * @throws IllegalStateException when the state does not allow it; the message is shown to the user
     */
    String startRecording(String name);

    /** Stops the active recording on every server; returns the ids the servers reported, normally one. */
    List<String> stopRecording();
}
