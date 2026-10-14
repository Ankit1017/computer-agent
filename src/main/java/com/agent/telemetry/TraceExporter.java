package com.agent.telemetry;

/**
 * Strategy interface for exporting completed SwarmSpans to in-memory buffers, local files, or collectors.
 */
@FunctionalInterface
public interface TraceExporter {

    /**
     * Called when a SwarmSpan has completed.
     *
     * @param span the completed immutable span
     */
    void export(SwarmSpan span);
}
