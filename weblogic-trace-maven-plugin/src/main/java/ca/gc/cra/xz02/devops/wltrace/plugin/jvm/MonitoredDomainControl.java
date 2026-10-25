package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.viewer.web.ControlState;
import ca.gc.cra.xz02.devops.wltrace.viewer.web.DomainControl;

import java.util.List;
import java.util.stream.Collectors;

/** The viewer's {@link DomainControl} over a {@link DomainStateMonitor}: the domain state as the page shows it, start and stop. */
public final class MonitoredDomainControl implements DomainControl {

    private final DomainStateMonitor monitor;

    public MonitoredDomainControl(DomainStateMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public ControlState state() {
        DomainState state = monitor.evaluate();
        String pluginAgentBuild = monitor.pluginAgentBuild();
        List<ControlState.Server> servers = state.servers.stream()
                .map(server -> new ControlState.Server(server.pid, server.serverName, server.agentLoaded, server.agentBuild,
                        server.agentBuild != null && server.agentBuild.equals(pluginAgentBuild),
                        server.recording, server.recordingId, server.recordingName, server.traceCount, server.droppedTraces))
                .collect(Collectors.toList());
        return new ControlState(state.kind.name(), state.message, state.ready(), state.enabledOnDisk(),
                state.blockPresent, state.jarPresent, state.jarCurrent,
                monitor.domain().home.toString(), monitor.domain().webLogicVersion(), pluginAgentBuild,
                state.activeRecordingId, state.activeRecordingName, servers);
    }

    @Override
    public String startRecording(String name) {
        return monitor.startRecording(name);
    }

    @Override
    public List<String> stopRecording() {
        return monitor.stopRecording();
    }
}
