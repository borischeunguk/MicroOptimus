/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

import org.agrona.DirectBuffer;
import org.agrona.sbe.*;


/**
 * Lightweight SOR response notification
 */
@SuppressWarnings("all")
public final class RoutingResponseDecoder implements MessageDecoderFlyweight
{
    public static final int BLOCK_LENGTH = 34;
    public static final int TEMPLATE_ID = 4;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final RoutingResponseDecoder parentMessage = this;
    private DirectBuffer buffer;
    private int initialOffset;
    private int offset;
    private int limit;
    int actingBlockLength;
    int actingVersion;

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

    public DirectBuffer buffer()
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

    public RoutingResponseDecoder wrap(
        final DirectBuffer buffer,
        final int offset,
        final int actingBlockLength,
        final int actingVersion)
    {
        if (buffer != this.buffer)
        {
            this.buffer = buffer;
        }
        this.initialOffset = offset;
        this.offset = offset;
        this.actingBlockLength = actingBlockLength;
        this.actingVersion = actingVersion;
        limit(offset + actingBlockLength);

        return this;
    }

    public RoutingResponseDecoder wrapAndApplyHeader(
        final DirectBuffer buffer,
        final int offset,
        final MessageHeaderDecoder headerDecoder)
    {
        headerDecoder.wrap(buffer, offset);

        final int templateId = headerDecoder.templateId();
        if (TEMPLATE_ID != templateId)
        {
            throw new IllegalStateException("Invalid TEMPLATE_ID: " + templateId);
        }

        return wrap(
            buffer,
            offset + MessageHeaderDecoder.ENCODED_LENGTH,
            headerDecoder.blockLength(),
            headerDecoder.version());
    }

    public RoutingResponseDecoder sbeRewind()
    {
        return wrap(buffer, initialOffset, actingBlockLength, actingVersion);
    }

    public int sbeDecodedLength()
    {
        final int currentLimit = limit();
        sbeSkip();
        final int decodedLength = encodedLength();
        limit(currentLimit);

        return decodedLength;
    }

    public int actingVersion()
    {
        return actingVersion;
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

    public long sequenceId()
    {
        return buffer.getLong(offset + 0, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public long orderId()
    {
        return buffer.getLong(offset + 8, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public short actionRaw()
    {
        return ((short)(buffer.getByte(offset + 16) & 0xFF));
    }

    public RoutingAction action()
    {
        return RoutingAction.get(((short)(buffer.getByte(offset + 16) & 0xFF)));
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

    public long messageLength()
    {
        return (buffer.getInt(offset + 17, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF_FFFFL);
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

    public long bufferOffset()
    {
        return buffer.getLong(offset + 21, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public long processingTime()
    {
        return (buffer.getInt(offset + 29, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF_FFFFL);
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

    public short venueCount()
    {
        return ((short)(buffer.getByte(offset + 33) & 0xFF));
    }


    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final RoutingResponseDecoder decoder = new RoutingResponseDecoder();
        decoder.wrap(buffer, initialOffset, actingBlockLength, actingVersion);

        return decoder.appendTo(new StringBuilder()).toString();
    }

    public StringBuilder appendTo(final StringBuilder builder)
    {
        if (null == buffer)
        {
            return builder;
        }

        final int originalLimit = limit();
        limit(initialOffset + actingBlockLength);
        builder.append("[RoutingResponse](sbeTemplateId=");
        builder.append(TEMPLATE_ID);
        builder.append("|sbeSchemaId=");
        builder.append(SCHEMA_ID);
        builder.append("|sbeSchemaVersion=");
        if (parentMessage.actingVersion != SCHEMA_VERSION)
        {
            builder.append(parentMessage.actingVersion);
            builder.append('/');
        }
        builder.append(SCHEMA_VERSION);
        builder.append("|sbeBlockLength=");
        if (actingBlockLength != BLOCK_LENGTH)
        {
            builder.append(actingBlockLength);
            builder.append('/');
        }
        builder.append(BLOCK_LENGTH);
        builder.append("):");
        builder.append("sequenceId=");
        builder.append(this.sequenceId());
        builder.append('|');
        builder.append("orderId=");
        builder.append(this.orderId());
        builder.append('|');
        builder.append("action=");
        builder.append(this.action());
        builder.append('|');
        builder.append("messageLength=");
        builder.append(this.messageLength());
        builder.append('|');
        builder.append("bufferOffset=");
        builder.append(this.bufferOffset());
        builder.append('|');
        builder.append("processingTime=");
        builder.append(this.processingTime());
        builder.append('|');
        builder.append("venueCount=");
        builder.append(this.venueCount());

        limit(originalLimit);

        return builder;
    }
    
    public RoutingResponseDecoder sbeSkip()
    {
        sbeRewind();

        return this;
    }
}
