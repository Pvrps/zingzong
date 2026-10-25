/**
 * Recording an HTTP exchange in full without linking against the servlet API.
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.http.TeeClasses} are generated against the API the server
 * loaded and delegate to {@link ca.gc.cra.xz02.devops.wltrace.agent.http.TeeSupport}, which copies every
 * byte of both bodies into the {@link ca.gc.cra.xz02.devops.wltrace.agent.http.BodyBuffer}s of an
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.http.HttpCapture}; {@link ca.gc.cra.xz02.devops.wltrace.agent.http.HttpInfoReader}
 * turns the completed capture into the span's HTTP facts through {@link ca.gc.cra.xz02.devops.wltrace.agent.http.ServletApi}.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.http;
