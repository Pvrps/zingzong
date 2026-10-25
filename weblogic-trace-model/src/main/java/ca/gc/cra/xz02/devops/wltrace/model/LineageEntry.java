package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** A value in a later input that came from an earlier output; detected by the plugin, never written by the agent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageEntry {

    public static final String CLASS_USER_INPUT = "userInput";
    public static final String CLASS_ENVIRONMENT = "environment";

    public LineageRef to;
    public LineageRef from;
    /** Normalized string form of the matched value. */
    public String value;
    /** Other earlier sources with the same value, nearest first, for manual override. */
    public List<LineageRef> alternatives;
    /** For values without a source: userInput or environment. */
    public String classification;
    /** The same value is also produced by this trace's own results: possibly recomputed, not flowed. */
    public Boolean alsoProducedHere;
}
