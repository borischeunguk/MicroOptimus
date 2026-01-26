/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

import org.agrona.DirectBuffer;
import org.agrona.sbe.*;


/**
 * SOR routing decision response
 */
@SuppressWarnings("all")
public final class RoutingDecisionDecoder implements MessageDecoderFlyweight
{
    public static final int BLOCK_LENGTH = 46;
    public static final int TEMPLATE_ID = 2;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final RoutingDecisionDecoder parentMessage = this;
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

    public RoutingDecisionDecoder wrap(
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

    public RoutingDecisionDecoder wrapAndApplyHeader(
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

    public RoutingDecisionDecoder sbeRewind()
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

    public long estimatedFillTime()
    {
        return buffer.getLong(offset + 17, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public long totalQuantity()
    {
        return buffer.getLong(offset + 25, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public long estimatedFees()
    {
        return buffer.getLong(offset + 33, java.nio.ByteOrder.LITTLE_ENDIAN);
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

    public long confidence()
    {
        return (buffer.getInt(offset + 41, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF_FFFFL);
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

    public byte rejectReason()
    {
        return buffer.getByte(offset + 45);
    }


    private final AllocationsDecoder allocations = new AllocationsDecoder(this);

    public static long allocationsDecoderId()
    {
        return 9;
    }

    public static int allocationsDecoderSinceVersion()
    {
        return 0;
    }

    /**
     * Venue allocations
     *
     * @return AllocationsDecoder : Venue allocations
     */
    public AllocationsDecoder allocations()
    {
        allocations.wrap(buffer);
        return allocations;
    }

    /**
     * Venue allocations
     */

    public static final class AllocationsDecoder
        implements Iterable<AllocationsDecoder>, java.util.Iterator<AllocationsDecoder>
    {
        public static final int HEADER_SIZE = 4;
        private final RoutingDecisionDecoder parentMessage;
        private DirectBuffer buffer;
        private int count;
        private int index;
        private int offset;
        private int blockLength;

        AllocationsDecoder(final RoutingDecisionDecoder parentMessage)
        {
            this.parentMessage = parentMessage;
        }

        public void wrap(final DirectBuffer buffer)
        {
            if (buffer != this.buffer)
            {
                this.buffer = buffer;
            }

            index = 0;
            final int limit = parentMessage.limit();
            parentMessage.limit(limit + HEADER_SIZE);
            blockLength = (buffer.getShort(limit + 0, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF);
            count = (buffer.getShort(limit + 2, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF);
        }

        public AllocationsDecoder next()
        {
            if (index >= count)
            {
                throw new java.util.NoSuchElementException();
            }

            offset = parentMessage.limit();
            parentMessage.limit(offset + blockLength);
            ++index;

            return this;
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

        public int actingBlockLength()
        {
            return blockLength;
        }

        public int count()
        {
            return count;
        }

        public java.util.Iterator<AllocationsDecoder> iterator()
        {
            return this;
        }

        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        public boolean hasNext()
        {
            return index < count;
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

        public short venueIdRaw()
        {
            return ((short)(buffer.getByte(offset + 0) & 0xFF));
        }

        public VenueId venueId()
        {
            return VenueId.get(((short)(buffer.getByte(offset + 0) & 0xFF)));
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

        public long quantity()
        {
            return buffer.getLong(offset + 1, java.nio.ByteOrder.LITTLE_ENDIAN);
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

        public short priority()
        {
            return ((short)(buffer.getByte(offset + 9) & 0xFF));
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

        public long estimatedLatency()
        {
            return buffer.getLong(offset + 10, java.nio.ByteOrder.LITTLE_ENDIAN);
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

        public long estimatedFees()
        {
            return buffer.getLong(offset + 18, java.nio.ByteOrder.LITTLE_ENDIAN);
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

        public long fillProbability()
        {
            return (buffer.getInt(offset + 26, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF_FFFFL);
        }


        public StringBuilder appendTo(final StringBuilder builder)
        {
            if (null == buffer)
            {
                return builder;
            }

            builder.append('(');
            builder.append("venueId=");
            builder.append(this.venueId());
            builder.append('|');
            builder.append("quantity=");
            builder.append(this.quantity());
            builder.append('|');
            builder.append("priority=");
            builder.append(this.priority());
            builder.append('|');
            builder.append("estimatedLatency=");
            builder.append(this.estimatedLatency());
            builder.append('|');
            builder.append("estimatedFees=");
            builder.append(this.estimatedFees());
            builder.append('|');
            builder.append("fillProbability=");
            builder.append(this.fillProbability());
            builder.append(')');

            return builder;
        }
        
        public AllocationsDecoder sbeSkip()
        {

            return this;
        }
    }

    public String toString()
    {
        if (null == buffer)
        {
            return "";
        }

        final RoutingDecisionDecoder decoder = new RoutingDecisionDecoder();
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
        builder.append("[RoutingDecision](sbeTemplateId=");
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
        builder.append("estimatedFillTime=");
        builder.append(this.estimatedFillTime());
        builder.append('|');
        builder.append("totalQuantity=");
        builder.append(this.totalQuantity());
        builder.append('|');
        builder.append("estimatedFees=");
        builder.append(this.estimatedFees());
        builder.append('|');
        builder.append("confidence=");
        builder.append(this.confidence());
        builder.append('|');
        builder.append("rejectReason=");
        builder.append(this.rejectReason());
        builder.append('|');
        builder.append("allocations=[");
        final int allocationsOriginalOffset = allocations.offset;
        final int allocationsOriginalIndex = allocations.index;
        final AllocationsDecoder allocations = this.allocations();
        if (allocations.count() > 0)
        {
            while (allocations.hasNext())
            {
                allocations.next().appendTo(builder);
                builder.append(',');
            }
            builder.setLength(builder.length() - 1);
        }
        allocations.offset = allocationsOriginalOffset;
        allocations.index = allocationsOriginalIndex;
        builder.append(']');

        limit(originalLimit);

        return builder;
    }
    
    public RoutingDecisionDecoder sbeSkip()
    {
        sbeRewind();
        AllocationsDecoder allocations = this.allocations();
        if (allocations.count() > 0)
        {
            while (allocations.hasNext())
            {
                allocations.next();
                allocations.sbeSkip();
            }
        }

        return this;
    }
}
