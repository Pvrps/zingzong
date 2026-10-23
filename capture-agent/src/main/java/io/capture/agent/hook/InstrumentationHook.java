package io.capture.agent.hook;

/**
 * A way of getting {@code Recorder.enter/exit} called around EJB invocations. Present only
 * while a recording is active, so an attached agent with no recording costs nothing per call.
 */
public interface InstrumentationHook {

    void install();

    void uninstall();

    boolean isInstalled();

    /** "annotation" or "container": what recording.json reports as the hook in force. */
    String name();

    InstrumentationHook NONE = new InstrumentationHook() {
        @Override
        public void install() {
        }

        @Override
        public void uninstall() {
        }

        @Override
        public boolean isInstalled() {
            return false;
        }

        @Override
        public String name() {
            return "none";
        }
    };
}
