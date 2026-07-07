package org.fog.dynacol.runtime;

/**
 * How colony overlay state is initialized before simulation.
 */
public enum OverlayBootstrapMode {
    /** DynaCol cold-start: incremental colony formation. */
    COLD_START,
    /** Fixed per-area colonies (d-* FCM), no bootstrap protocol. */
    STATIC_PRECLUSTERED,
    /** Single shared CRT (flat / centralized FSC view). */
    FLAT,
    /** No colony overlay (e.g. Edgeward). */
    NONE
}
