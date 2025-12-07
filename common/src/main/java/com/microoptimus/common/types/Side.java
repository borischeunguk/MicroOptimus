package com.microoptimus.common.types;

/**
 * Order side enumeration
 */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}

