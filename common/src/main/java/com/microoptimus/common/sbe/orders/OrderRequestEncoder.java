/* Generated SBE (Simple Binary Encoding) message codec. */
package com.microoptimus.common.sbe.orders;

import org.agrona.MutableDirectBuffer;
import org.agrona.sbe.*;


/**
 * Order routing request for zero-JNI SOR communication
 */
@SuppressWarnings("all")
public final class OrderRequestEncoder implements MessageEncoderFlyweight
{
    public static final int BLOCK_LENGTH = 80;
    public static final int TEMPLATE_ID = 1;
    public static final int SCHEMA_ID = 1;
    public static final int SCHEMA_VERSION = 1;
    public static final String SEMANTIC_VERSION = "1.0";
    public static final java.nio.ByteOrder BYTE_ORDER = java.nio.ByteOrder.LITTLE_ENDIAN;

    private final OrderRequestEncoder parentMessage = this;
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

    public OrderRequestEncoder wrap(final MutableDirectBuffer buffer, final int offset)
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

    public OrderRequestEncoder wrapAndApplyHeader(
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

    public OrderRequestEncoder sequenceId(final long value)
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

    public OrderRequestEncoder orderId(final long value)
    {
        buffer.putLong(offset + 8, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int symbolId()
    {
        return 3;
    }

    public static int symbolSinceVersion()
    {
        return 0;
    }

    public static int symbolEncodingOffset()
    {
        return 16;
    }

    public static int symbolEncodingLength()
    {
        return 16;
    }

    public static String symbolMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static byte symbolNullValue()
    {
        return (byte)0;
    }

    public static byte symbolMinValue()
    {
        return (byte)32;
    }

    public static byte symbolMaxValue()
    {
        return (byte)126;
    }

    public static int symbolLength()
    {
        return 16;
    }


    public OrderRequestEncoder symbol(final int index, final byte value)
    {
        if (index < 0 || index >= 16)
        {
            throw new IndexOutOfBoundsException("index out of range: index=" + index);
        }

        final int pos = offset + 16 + (index * 1);
        buffer.putByte(pos, value);

        return this;
    }

    public static String symbolCharacterEncoding()
    {
        return java.nio.charset.StandardCharsets.US_ASCII.name();
    }

    public OrderRequestEncoder putSymbol(final byte[] src, final int srcOffset)
    {
        final int length = 16;
        if (srcOffset < 0 || srcOffset > (src.length - length))
        {
            throw new IndexOutOfBoundsException("Copy will go out of range: offset=" + srcOffset);
        }

        buffer.putBytes(offset + 16, src, srcOffset, length);

        return this;
    }

    public OrderRequestEncoder symbol(final String src)
    {
        final int length = 16;
        final int srcLength = null == src ? 0 : src.length();
        if (srcLength > length)
        {
            throw new IndexOutOfBoundsException("String too large for copy: byte length=" + srcLength);
        }

        buffer.putStringWithoutLengthAscii(offset + 16, src);

        for (int start = srcLength; start < length; ++start)
        {
            buffer.putByte(offset + 16 + start, (byte)0);
        }

        return this;
    }

    public OrderRequestEncoder symbol(final CharSequence src)
    {
        final int length = 16;
        final int srcLength = null == src ? 0 : src.length();
        if (srcLength > length)
        {
            throw new IndexOutOfBoundsException("CharSequence too large for copy: byte length=" + srcLength);
        }

        buffer.putStringWithoutLengthAscii(offset + 16, src);

        for (int start = srcLength; start < length; ++start)
        {
            buffer.putByte(offset + 16 + start, (byte)0);
        }

        return this;
    }

    public static int sideId()
    {
        return 4;
    }

    public static int sideSinceVersion()
    {
        return 0;
    }

    public static int sideEncodingOffset()
    {
        return 32;
    }

    public static int sideEncodingLength()
    {
        return 1;
    }

    public static String sideMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public OrderRequestEncoder side(final Side value)
    {
        buffer.putByte(offset + 32, (byte)value.value());
        return this;
    }

    public static int orderTypeId()
    {
        return 5;
    }

    public static int orderTypeSinceVersion()
    {
        return 0;
    }

    public static int orderTypeEncodingOffset()
    {
        return 33;
    }

    public static int orderTypeEncodingLength()
    {
        return 1;
    }

    public static String orderTypeMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public OrderRequestEncoder orderType(final OrderType value)
    {
        buffer.putByte(offset + 33, (byte)value.value());
        return this;
    }

    public static int priceId()
    {
        return 6;
    }

    public static int priceSinceVersion()
    {
        return 0;
    }

    public static int priceEncodingOffset()
    {
        return 34;
    }

    public static int priceEncodingLength()
    {
        return 8;
    }

    public static String priceMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long priceNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long priceMinValue()
    {
        return 0x0L;
    }

    public static long priceMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public OrderRequestEncoder price(final long value)
    {
        buffer.putLong(offset + 34, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int quantityId()
    {
        return 7;
    }

    public static int quantitySinceVersion()
    {
        return 0;
    }

    public static int quantityEncodingOffset()
    {
        return 42;
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

    public OrderRequestEncoder quantity(final long value)
    {
        buffer.putLong(offset + 42, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int timestampId()
    {
        return 8;
    }

    public static int timestampSinceVersion()
    {
        return 0;
    }

    public static int timestampEncodingOffset()
    {
        return 50;
    }

    public static int timestampEncodingLength()
    {
        return 8;
    }

    public static String timestampMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long timestampNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long timestampMinValue()
    {
        return 0x0L;
    }

    public static long timestampMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public OrderRequestEncoder timestamp(final long value)
    {
        buffer.putLong(offset + 50, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int algorithmId()
    {
        return 9;
    }

    public static int algorithmSinceVersion()
    {
        return 0;
    }

    public static int algorithmEncodingOffset()
    {
        return 58;
    }

    public static int algorithmEncodingLength()
    {
        return 1;
    }

    public static String algorithmMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public OrderRequestEncoder algorithm(final Algorithm value)
    {
        buffer.putByte(offset + 58, (byte)value.value());
        return this;
    }

    public static int maxLatencyNanosId()
    {
        return 10;
    }

    public static int maxLatencyNanosSinceVersion()
    {
        return 0;
    }

    public static int maxLatencyNanosEncodingOffset()
    {
        return 59;
    }

    public static int maxLatencyNanosEncodingLength()
    {
        return 8;
    }

    public static String maxLatencyNanosMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long maxLatencyNanosNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long maxLatencyNanosMinValue()
    {
        return 0x0L;
    }

    public static long maxLatencyNanosMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public OrderRequestEncoder maxLatencyNanos(final long value)
    {
        buffer.putLong(offset + 59, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int clientIdId()
    {
        return 11;
    }

    public static int clientIdSinceVersion()
    {
        return 0;
    }

    public static int clientIdEncodingOffset()
    {
        return 67;
    }

    public static int clientIdEncodingLength()
    {
        return 4;
    }

    public static String clientIdMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long clientIdNullValue()
    {
        return 4294967295L;
    }

    public static long clientIdMinValue()
    {
        return 0L;
    }

    public static long clientIdMaxValue()
    {
        return 4294967294L;
    }

    public OrderRequestEncoder clientId(final long value)
    {
        buffer.putInt(offset + 67, (int)value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int minFillQtyId()
    {
        return 12;
    }

    public static int minFillQtySinceVersion()
    {
        return 0;
    }

    public static int minFillQtyEncodingOffset()
    {
        return 71;
    }

    public static int minFillQtyEncodingLength()
    {
        return 8;
    }

    public static String minFillQtyMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static long minFillQtyNullValue()
    {
        return 0xffffffffffffffffL;
    }

    public static long minFillQtyMinValue()
    {
        return 0x0L;
    }

    public static long minFillQtyMaxValue()
    {
        return 0xfffffffffffffffeL;
    }

    public OrderRequestEncoder minFillQty(final long value)
    {
        buffer.putLong(offset + 71, value, java.nio.ByteOrder.LITTLE_ENDIAN);
        return this;
    }


    public static int timeInForceId()
    {
        return 13;
    }

    public static int timeInForceSinceVersion()
    {
        return 0;
    }

    public static int timeInForceEncodingOffset()
    {
        return 79;
    }

    public static int timeInForceEncodingLength()
    {
        return 1;
    }

    public static String timeInForceMetaAttribute(final MetaAttribute metaAttribute)
    {
        if (MetaAttribute.PRESENCE == metaAttribute)
        {
            return "required";
        }

        return "";
    }

    public static short timeInForceNullValue()
    {
        return (short)255;
    }

    public static short timeInForceMinValue()
    {
        return (short)0;
    }

    public static short timeInForceMaxValue()
    {
        return (short)254;
    }

    public OrderRequestEncoder timeInForce(final short value)
    {
        buffer.putByte(offset + 79, (byte)value);
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

        final OrderRequestDecoder decoder = new OrderRequestDecoder();
        decoder.wrap(buffer, initialOffset, BLOCK_LENGTH, SCHEMA_VERSION);

        return decoder.appendTo(builder);
    }
}
