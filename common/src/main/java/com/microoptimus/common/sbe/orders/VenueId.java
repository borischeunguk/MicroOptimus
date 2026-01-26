/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

@SuppressWarnings("all")
public enum VenueId
{
    INTERNAL((short)1),

    CME((short)2),

    NASDAQ((short)3),

    NYSE((short)4),

    ARCA((short)5),

    IEX((short)6),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    VenueId(final short value)
    {
        this.value = value;
    }

    /**
     * The raw encoded value in the Java type representation.
     *
     * @return the raw value encoded.
     */
    public short value()
    {
        return value;
    }

    /**
     * Lookup the enum value representing the value.
     *
     * @param value encoded to be looked up.
     * @return the enum value representing the value.
     */
    public static VenueId get(final short value)
    {
        switch (value)
        {
            case 1: return INTERNAL;
            case 2: return CME;
            case 3: return NASDAQ;
            case 4: return NYSE;
            case 5: return ARCA;
            case 6: return IEX;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
