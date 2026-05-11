package com.microoptimus.javamvp.common;

public final class ShmRef {
    public final int regionId;
    public final int msgType;
    public final long offset;
    public final int length;
    public final long seq;

    public ShmRef(int regionId, int msgType, long offset, int length, long seq) {
        this.regionId = regionId;
        this.msgType = msgType;
        this.offset = offset;
        this.length = length;
        this.seq = seq;
    }
}

