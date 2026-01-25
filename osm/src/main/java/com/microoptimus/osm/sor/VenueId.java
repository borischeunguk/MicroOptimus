package com.microoptimus.osm.sor;

/**
 * VenueId - Supported trading venues
 */
public enum VenueId {
    INTERNAL,   // Internal orderbook (internaliser)
    CME,        // CME Group (iLink3)
    NASDAQ,     // Nasdaq (OUCH)
    NYSE,       // NYSE (FIX)
    ARCA,       // NYSE Arca
    IEX;        // IEX

    public boolean isInternal() {
        return this == INTERNAL;
    }

    public boolean isExternal() {
        return this != INTERNAL;
    }
}
