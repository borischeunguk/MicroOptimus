package com.microoptimus.algo.state;

/**
 * AlgoOrderState - Lifecycle states for algo orders
 */
public enum AlgoOrderState {
    PENDING,       // Created, awaiting start
    WORKING,       // Actively generating slices
    PAUSED,        // Temporarily paused
    PARTIAL_FILL,  // Has some fills, still working
    FILLED,        // Fully executed
    CANCELLED,     // User cancelled
    REJECTED,      // Rejected by system
    EXPIRED;       // End time reached before completion

    /**
     * Check if this state allows slice generation
     */
    public boolean canGenerateSlices() {
        return this == WORKING || this == PARTIAL_FILL;
    }

    /**
     * Check if this state can transition to WORKING
     */
    public boolean canStart() {
        return this == PENDING;
    }

    /**
     * Check if this state can be paused
     */
    public boolean canPause() {
        return this == WORKING || this == PARTIAL_FILL;
    }

    /**
     * Check if this state can be resumed
     */
    public boolean canResume() {
        return this == PAUSED;
    }

    /**
     * Check if this state can be cancelled
     */
    public boolean canCancel() {
        return this == PENDING || this == WORKING ||
               this == PAUSED || this == PARTIAL_FILL;
    }

    /**
     * Check if this is a terminal state
     */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED ||
               this == REJECTED || this == EXPIRED;
    }

    /**
     * Check if this is an active state
     */
    public boolean isActive() {
        return this == WORKING || this == PARTIAL_FILL || this == PAUSED;
    }
}
