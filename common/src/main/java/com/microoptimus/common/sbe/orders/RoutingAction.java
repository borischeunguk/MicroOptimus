/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

@SuppressWarnings("all")
public enum RoutingAction
{
    ROUTE_EXTERNAL((short)0),

    ROUTE_INTERNAL((short)1),

    SPLIT_ORDER((short)2),

    REJECT((short)3),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    RoutingAction(final short value)
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
    public static RoutingAction get(final short value)
    {
        switch (value)
        {
            case 0: return ROUTE_EXTERNAL;
            case 1: return ROUTE_INTERNAL;
            case 2: return SPLIT_ORDER;
            case 3: return REJECT;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
