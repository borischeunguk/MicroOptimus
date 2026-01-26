/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

import org.agrona.MutableDirectBuffer;
import org.agrona.sbe.*;


/**
 * Lightweight SOR response notification
 */
@SuppressWarnings("all")
public final class RoutingResponseEncoder implements MessageEncoderFlyweight
{
    public static final int BLOCK_LENGTH = 34;
    public static final int TEMPLATE_ID = 4;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final RoutingResponseEncoder parentMessage = this;
    private MutableDirectBuffer buffer;
    private int initialOffset;
    private int offset;
    private int limit;

    public int sbeBlockLength()
    {
        return BLOCK_LENGTH;
    }

    public int sbeTemplateId()
    {
        return TEMPLATE_ID;
    }

    public int sbeSchemaId()
    {
        return SCHEMA_ID;
    }

    public int sbeSchemaVersion()
    {
        return SCHEMA_VERSION;
    }

    public String sbeSemanticType()
    {
        return "";
    }

    public MutableDirectBuffer buffer()
    {
        return buffer;
    }

    public int initialOffset()
    {
        return initialOffset;
    }

    public int offset()
    {
        return offset;
    }

    public RoutingResponseEncoder wrap(final MutableDirectBuffer buffer, final int offset)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.initialOffset = offset;
        this.offset = offset;
        limit(offset + BLOCK_LENGTH);

        return this;
    }

    public RoutingResponseEncoder wrapAndApplyHeader(
        final MutableDirectBuffer buffer, final int offset, final MessageHeaderEncoder headerEncoder)
    {
        headerEncoder
            .wrap(buffer, offset)
            .blockLength(BLOCK_LENGTH)
            .templateId(TEMPLATE_ID)
            .schemaId(SCHEMA_ID)
            .version(SCHEMA_VERSION);

        return wrap(buffer, offset + MessageHeaderEncoder.ENCODED_LENGTH);
    }

    public int encodedLength()
    {
        return limit - offset;
    }

    public int limit()
    {
        return limit;
    }

    public void limit(final int limit)
    {
        this.limit = limit;
    }

    public static int sequenceIdId()
    {
        return 1;
    }

    public static int sequenceIdSinceVersion()
    {
        return 0;
    }

    public static int sequenceIdEncodingOffset()
    {
        return 0;
    }

    public static int sequenceIdEncodingLength()
    {
        return 8;
    }

    public static String sequenceIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long sequenceIdNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long sequenceIdMinValue()
    {
        return 0x0L;
    }

    public static long sequenceIdMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public RoutingResponseEncoder sequenceId(final long value)
    {
        buffer.putLong(offset + 0, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int orderIdId()
    {
        return 2;
    }

    public static int orderIdSinceVersion()
    {
        return 0;
    }

    public static int orderIdEncodingOffset()
    {
        return 8;
    }

    public static int orderIdEncodingLength()
    {
        return 8;
    }

    public static String orderIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long orderIdNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long orderIdMinValue()
    {
        return 0x0L;
    }

    public static long orderIdMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public RoutingResponseEncoder orderId(final long value)
    {
        buffer.putLong(offset + 8, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int actionId()
    {
        return 3;
    }

    public static int actionSinceVersion()
    {
        return 0;
    }

    public static int actionEncodingOffset()
    {
        return 16;
    }

    public static int actionEncodingLength()
    {
        return 1;
    }

    public static String actionMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public RoutingResponseEncoder action(final RoutingAction value)
    {
        buffer.putByte(offset + 16, (byte)value.value());
        return this;
    }

    public static int messageLengthId()
    {
        return 4;
    }

    public static int messageLengthSinceVersion()
    {
        return 0;
    }

    public static int messageLengthEncodingOffset()
    {
        return 17;
    }

    public static int messageLengthEncodingLength()
    {
        return 4;
    }

    public static String messageLengthMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long messageLengthNullValue()
    {
        return 4294967295L;
    }

    public static long messageLengthMinValue()
    {
        return 0L;
    }

    public static long messageLengthMaxValue()
    {
        return 4294967294L;
    }

    public RoutingResponseEncoder messageLength(final long value)
    {
        buffer.putInt(offset + 17, (int)value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int bufferOffsetId()
    {
        return 5;
    }

    public static int bufferOffsetSinceVersion()
    {
        return 0;
    }

    public static int bufferOffsetEncodingOffset()
    {
        return 21;
    }

    public static int bufferOffsetEncodingLength()
    {
        return 8;
    }

    public static String bufferOffsetMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long bufferOffsetNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long bufferOffsetMinValue()
    {
        return 0x0L;
    }

    public static long bufferOffsetMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public RoutingResponseEncoder bufferOffset(final long value)
    {
        buffer.putLong(offset + 21, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int processingTimeId()
    {
        return 6;
    }

    public static int processingTimeSinceVersion()
    {
        return 0;
    }

    public static int processingTimeEncodingOffset()
    {
        return 29;
    }

    public static int processingTimeEncodingLength()
    {
        return 4;
    }

    public static String processingTimeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long processingTimeNullValue()
    {
        return 4294967295L;
    }

    public static long processingTimeMinValue()
    {
        return 0L;
    }

    public static long processingTimeMaxValue()
    {
        return 4294967294L;
    }

    public RoutingResponseEncoder processingTime(final long value)
    {
        buffer.putInt(offset + 29, (int)value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int venueCountId()
    {
        return 7;
    }

    public static int venueCountSinceVersion()
    {
        return 0;
    }

    public static int venueCountEncodingOffset()
    {
        return 33;
    }

    public static int venueCountEncodingLength()
    {
        return 1;
    }

    public static String venueCountMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short venueCountNullValue()
    {
        return (short)255;
    }

    public static short venueCountMinValue()
    {
        return (short)0;
    }

    public static short venueCountMaxValue()
    {
        return (short)254;
    }

    public RoutingResponseEncoder venueCount(final short value)
    {
        buffer.putByte(offset + 33, (byte)value);
        return this;
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        return appendTo(new StringBuilder()).toString();
    }

    public StringBuilder appendTo(final StringBuilder builder)
    {
        if (null == buffer)
        {
            return builder;
        }

        final RoutingResponseDecoder decoder = new RoutingResponseDecoder();
        decoder.wrap(buffer, initialOffset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
