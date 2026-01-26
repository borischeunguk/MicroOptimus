/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

import org.agrona.MutableDirectBuffer;
import org.agrona.sbe.*;


/**
 * SOR routing decision response
 */
@SuppressWarnings("all")
public final class RoutingDecisionEncoder implements MessageEncoderFlyweight
{
    public static final int BLOCK_LENGTH = 46;
    public static final int TEMPLATE_ID = 2;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final RoutingDecisionEncoder parentMessage = this;
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

    public RoutingDecisionEncoder wrap(final MutableDirectBuffer buffer, final int offset)
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

    public RoutingDecisionEncoder wrapAndApplyHeader(
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

    public RoutingDecisionEncoder sequenceId(final long value)
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

    public RoutingDecisionEncoder orderId(final long value)
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

    public RoutingDecisionEncoder action(final RoutingAction value)
    {
        buffer.putByte(offset + 16, (byte)value.value());
        return this;
    }

    public static int estimatedFillTimeId()
    {
        return 4;
    }

    public static int estimatedFillTimeSinceVersion()
    {
        return 0;
    }

    public static int estimatedFillTimeEncodingOffset()
    {
        return 17;
    }

    public static int estimatedFillTimeEncodingLength()
    {
        return 8;
    }

    public static String estimatedFillTimeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long estimatedFillTimeNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long estimatedFillTimeMinValue()
    {
        return 0x0L;
    }

    public static long estimatedFillTimeMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public RoutingDecisionEncoder estimatedFillTime(final long value)
    {
        buffer.putLong(offset + 17, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int totalQuantityId()
    {
        return 5;
    }

    public static int totalQuantitySinceVersion()
    {
        return 0;
    }

    public static int totalQuantityEncodingOffset()
    {
        return 25;
    }

    public static int totalQuantityEncodingLength()
    {
        return 8;
    }

    public static String totalQuantityMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long totalQuantityNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long totalQuantityMinValue()
    {
        return 0x0L;
    }

    public static long totalQuantityMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public RoutingDecisionEncoder totalQuantity(final long value)
    {
        buffer.putLong(offset + 25, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int estimatedFeesId()
    {
        return 6;
    }

    public static int estimatedFeesSinceVersion()
    {
        return 0;
    }

    public static int estimatedFeesEncodingOffset()
    {
        return 33;
    }

    public static int estimatedFeesEncodingLength()
    {
        return 8;
    }

    public static String estimatedFeesMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long estimatedFeesNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long estimatedFeesMinValue()
    {
        return 0x0L;
    }

    public static long estimatedFeesMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public RoutingDecisionEncoder estimatedFees(final long value)
    {
        buffer.putLong(offset + 33, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int confidenceId()
    {
        return 7;
    }

    public static int confidenceSinceVersion()
    {
        return 0;
    }

    public static int confidenceEncodingOffset()
    {
        return 41;
    }

    public static int confidenceEncodingLength()
    {
        return 4;
    }

    public static String confidenceMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long confidenceNullValue()
    {
        return 4294967295L;
    }

    public static long confidenceMinValue()
    {
        return 0L;
    }

    public static long confidenceMaxValue()
    {
        return 4294967294L;
    }

    public RoutingDecisionEncoder confidence(final long value)
    {
        buffer.putInt(offset + 41, (int)value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int rejectReasonId()
    {
        return 8;
    }

    public static int rejectReasonSinceVersion()
    {
        return 0;
    }

    public static int rejectReasonEncodingOffset()
    {
        return 45;
    }

    public static int rejectReasonEncodingLength()
    {
        return 1;
    }

    public static String rejectReasonMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte rejectReasonNullValue()
    {
        return (byte)0;
    }

    public static byte rejectReasonMinValue()
    {
        return (byte)32;
    }

    public static byte rejectReasonMaxValue()
    {
        return (byte)126;
    }

    public RoutingDecisionEncoder rejectReason(final byte value)
    {
        buffer.putByte(offset + 45, value);
        return this;
    }


    private final AllocationsEncoder allocations = new AllocationsEncoder(this);

    public static long allocationsId()
    {
        return 9;
    }

    /**
     * Venue allocations
     *
     * @param count of times the group will be encoded.
     * @return AllocationsEncoder : encoder for the group.
     */
    public AllocationsEncoder allocationsCount(final int count)
    {
        allocations.wrap(buffer, count);
        return allocations;
    }

    /**
     * Venue allocations
     */

    public static final class AllocationsEncoder
    {
        public static final int HEADER_SIZE = 4;
        private final RoutingDecisionEncoder parentMessage;
        private MutableDirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int initialLimit;

        AllocationsEncoder(final RoutingDecisionEncoder parentMessage)
        {
            this.parentMessage = parentMessage;
        }

        public void wrap(final MutableDirectBuffer buffer, final int count)
        {
            if (count < 0 || count > 65534)
            {
                throw new IllegalArgumentException("count outside allowed range: count=" + count);
            }

            if (buffer != this.buffer)
            {
                this.buffer = buffer;
            }

            index = 0;
            this.count = count;
            final int limit = parentMessage.limit();
            initialLimit = limit;
            parentMessage.limit(limit + HEADER_SIZE);
            buffer.putShort(limit + 0, (short)30, java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.putShort(limit + 2, (short)count, java.nio.ByteOrder.LITTLE_ENDIAN);
        }

        public AllocationsEncoder next()
        {
            if (index >= count)
            {
                throw new java.util.NoSuchElementException();
            }

            offset = parentMessage.limit();
            parentMessage.limit(offset + sbeBlockLength());
            ++index;

            return this;
        }

        public int resetCountToIndex()
        {
            count = index;
            buffer.putShort(initialLimit + 2, (short)count, java.nio.ByteOrder.LITTLE_ENDIAN);

            return count;
        }

        public static int countMinValue()
        {
            return 0;
        }

        public static int countMaxValue()
        {
            return 65534;
        }

        public static int sbeHeaderSize()
        {
            return HEADER_SIZE;
        }

        public static int sbeBlockLength()
        {
            return 30;
        }

        public static int venueIdId()
        {
            return 10;
        }

        public static int venueIdSinceVersion()
        {
            return 0;
        }

        public static int venueIdEncodingOffset()
        {
            return 0;
        }

        public static int venueIdEncodingLength()
        {
            return 1;
        }

        public static String venueIdMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public AllocationsEncoder venueId(final VenueId value)
        {
            buffer.putByte(offset + 0, (byte)value.value());
            return this;
        }

        public static int quantityId()
        {
            return 11;
        }

        public static int quantitySinceVersion()
        {
            return 0;
        }

        public static int quantityEncodingOffset()
        {
            return 1;
        }

        public static int quantityEncodingLength()
        {
            return 8;
        }

        public static String quantityMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long quantityNullValue()
        {
            return 0xffffffffffffffffL;
        }

        public static long quantityMinValue()
        {
            return 0x0L;
        }

        public static long quantityMaxValue()
        {
            return 0xfffffffffffffffeL;
        }

        public AllocationsEncoder quantity(final long value)
        {
            buffer.putLong(offset + 1, value, java.nio.ByteOrder.LITTLE_ENDIAN);
            return this;
        }


        public static int priorityId()
        {
            return 12;
        }

        public static int prioritySinceVersion()
        {
            return 0;
        }

        public static int priorityEncodingOffset()
        {
            return 9;
        }

        public static int priorityEncodingLength()
        {
            return 1;
        }

        public static String priorityMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static short priorityNullValue()
        {
            return (short)255;
        }

        public static short priorityMinValue()
        {
            return (short)0;
        }

        public static short priorityMaxValue()
        {
            return (short)254;
        }

        public AllocationsEncoder priority(final short value)
        {
            buffer.putByte(offset + 9, (byte)value);
            return this;
        }


        public static int estimatedLatencyId()
        {
            return 13;
        }

        public static int estimatedLatencySinceVersion()
        {
            return 0;
        }

        public static int estimatedLatencyEncodingOffset()
        {
            return 10;
        }

        public static int estimatedLatencyEncodingLength()
        {
            return 8;
        }

        public static String estimatedLatencyMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long estimatedLatencyNullValue()
        {
            return 0xffffffffffffffffL;
        }

        public static long estimatedLatencyMinValue()
        {
            return 0x0L;
        }

        public static long estimatedLatencyMaxValue()
        {
            return 0xfffffffffffffffeL;
        }

        public AllocationsEncoder estimatedLatency(final long value)
        {
            buffer.putLong(offset + 10, value, java.nio.ByteOrder.LITTLE_ENDIAN);
            return this;
        }


        public static int estimatedFeesId()
        {
            return 14;
        }

        public static int estimatedFeesSinceVersion()
        {
            return 0;
        }

        public static int estimatedFeesEncodingOffset()
        {
            return 18;
        }

        public static int estimatedFeesEncodingLength()
        {
            return 8;
        }

        public static String estimatedFeesMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long estimatedFeesNullValue()
        {
            return 0xffffffffffffffffL;
        }

        public static long estimatedFeesMinValue()
        {
            return 0x0L;
        }

        public static long estimatedFeesMaxValue()
        {
            return 0xfffffffffffffffeL;
        }

        public AllocationsEncoder estimatedFees(final long value)
        {
            buffer.putLong(offset + 18, value, java.nio.ByteOrder.LITTLE_ENDIAN);
            return this;
        }


        public static int fillProbabilityId()
        {
            return 15;
        }

        public static int fillProbabilitySinceVersion()
        {
            return 0;
        }

        public static int fillProbabilityEncodingOffset()
        {
            return 26;
        }

        public static int fillProbabilityEncodingLength()
        {
            return 4;
        }

        public static String fillProbabilityMetaAttribute(final MetaAttribute metaAttribute)
        {
            if (MetaAttribute.PRESENCE == metaAttribute)
            {
                return "required";
            }

            return "";
        }

        public static long fillProbabilityNullValue()
        {
            return 4294967295L;
        }

        public static long fillProbabilityMinValue()
        {
            return 0L;
        }

        public static long fillProbabilityMaxValue()
        {
            return 4294967294L;
        }

        public AllocationsEncoder fillProbability(final long value)
        {
            buffer.putInt(offset + 26, (int)value, java.nio.ByteOrder.LITTLE_ENDIAN);
            return this;
        }

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

        final RoutingDecisionDecoder decoder = new RoutingDecisionDecoder();
        decoder.wrap(buffer, initialOffset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
