package com.microoptimus.liquidator;

import java.util.HashMap;
import java.util.Map;

/**
 * OrderIDMapper - Maps internal order IDs to CME order IDs
 */
public class OrderIDMapper {

    private final Map<Long, Long> internalToCme = new HashMap<>();
    private final Map<Long, Long> cmeToInternal = new HashMap<>();

    /**
     * Map internal order ID to CME order ID
     */
    public void mapOrder(long internalOrderId, long cmeOrderId) {
        internalToCme.put(internalOrderId, cmeOrderId);
        cmeToInternal.put(cmeOrderId, internalOrderId);
    }

    /**
     * Get CME order ID for internal order ID
     */
    public Long getCmeOrderId(long internalOrderId) {
        return internalToCme.get(internalOrderId);
    }

    /**
     * Get internal order ID for CME order ID
     */
    public Long getInternalOrderId(long cmeOrderId) {
        return cmeToInternal.get(cmeOrderId);
    }

    /**
     * Remove mapping
     */
    public void removeMapping(long internalOrderId) {
        Long cmeOrderId = internalToCme.remove(internalOrderId);
        if (cmeOrderId != null) {
            cmeToInternal.remove(cmeOrderId);
        }
    }
}

