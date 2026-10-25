package ca.gc.cra.xz02.devops.wltrace.agent.record;

import java.util.concurrent.atomic.AtomicReference;

/** The recording in progress, shared between the lifecycle that starts and stops it and the recorders that consult it on every call. */
public final class ActiveRecording {

    private final AtomicReference<Recording> current = new AtomicReference<>();

    /** The active recording, or null when nothing is to be recorded. */
    public Recording get() {
        return current.get();
    }

    /** Replaces the active recording and returns the previous one, or null. */
    Recording replace(Recording recording) {
        return current.getAndSet(recording);
    }
}
