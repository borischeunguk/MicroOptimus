/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

@SuppressWarnings("all")
public enum Algorithm
{
    SIMPLE((short)1),

    TWAP((short)2),

    VWAP((short)3),

    POV((short)4),

    ICEBERG((short)5),

    /**
     * To be used to represent not present or null.
     */
    NULL_VAL((short)255);

    private final short value;

    Algorithm(final short value)
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
    public static Algorithm get(final short value)
    {
        switch (value)
        {
            case 1: return SIMPLE;
            case 2: return TWAP;
            case 3: return VWAP;
            case 4: return POV;
            case 5: return ICEBERG;
            case 255: return NULL_VAL;
        }

        throw new IllegalArgumentException("Unknown value: " + value);
    }
}
