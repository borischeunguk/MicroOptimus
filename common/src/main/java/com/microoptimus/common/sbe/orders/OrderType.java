/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

@SuppressWarnings("all")
public enum OrderType
{
    MARKET((short)0),

    LIMIT((short)1),

    STOP((short)2),

    STOP_LIMIT((short)3),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    OrderType(final short value)
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
    public static OrderType get(final short value)
    {
        switch (value)
        {
            case 0: return MARKET;
            case 1: return LIMIT;
            case 2: return STOP;
            case 3: return STOP_LIMIT;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
