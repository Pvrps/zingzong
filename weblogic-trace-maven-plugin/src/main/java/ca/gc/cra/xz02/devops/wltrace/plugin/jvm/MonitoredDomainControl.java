package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.viewer.web.ControlState;
import ca.gc.cra.xz02.devops.wltrace.viewer.web.DomainControl;

import java.util.ArrayList;
import java.util.List;

/** The viewer's {@link DomainControl} over a {@link StateMonitor}: the domain state as the page shows it, start and stop. */
public final class MonitoredDomainControl implements DomainControl {

    private final StateMonitor monitor;

    public MonitoredDomainControl(StateMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public ControlState state() {
        DomainState state = monitor.evaluate();
        List<ControlState.Server> servers = new ArrayList<>();
        for (DomainState.Server server : state.servers) {
            servers.add(new ControlState.Server(server.pid, server.serverName, server.agentLoaded, server.agentBuild,
                    server.agentBuild != null && server.agentBuild.equals(monitor.pluginAgentBuild()),
                    server.recording, server.recordingId, server.recordingName, server.traceCount, server.droppedTraces));
        }
        return new ControlState(state.kind.name(), state.message, state.ready(), state.enabledOnDisk(),
                state.blockPresent, state.jarPresent, state.jarCurrent,
                monitor.domain().home.toString(), monitor.domain().webLogicVersion(), monitor.pluginAgentBuild(),
                state.activeRecordingId, state.activeRecordingName, servers);
    }

    @Override
    public String startRecording(String name) {
        try {
            return monitor.startCapture(name);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public List<String> stopRecording() {
        return monitor.stopCapture();
    }
}
