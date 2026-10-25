/**
 * A WebLogic domain on disk. {@link ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain} is the home and
 * its files; {@link ca.gc.cra.xz02.devops.wltrace.plugin.domain.DomainPlatform} and its two subclasses hold
 * every difference between Unix and Windows start scripts; {@link ca.gc.cra.xz02.devops.wltrace.plugin.domain.OverridesBlock}
 * writes and removes the agent's block in the overrides file; {@link ca.gc.cra.xz02.devops.wltrace.plugin.domain.DomainJdk}
 * and {@link ca.gc.cra.xz02.devops.wltrace.plugin.domain.NodeManagerSettings} read the facts that decide what the block holds.
 */
package ca.gc.cra.xz02.devops.wltrace.plugin.domain;
