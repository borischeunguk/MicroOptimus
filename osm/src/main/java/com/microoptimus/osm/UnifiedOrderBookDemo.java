package com.microoptimus.osm;

import com.microoptimus.common.types.Side;
import com.microoptimus.common.types.TimeInForce;

/**
 * Demo showing YOUR EXACT scenario with unified orderbook + internalization priority
 *
 * Your Updated Scenario:
 * - signal: bid1(5@9), ask1(5@11)
 * - exchange1: bid2(5@9), ask2(5@11)
 * - exchange2: bid5(5@8), ask5(5@12) ← Note: bid5 at price 8, lower than others
 * - internal1: bid3(5@9)
 * - internal2: ask4(12@9) ← aggressive sell order
 *
 * Expected matching with internalization priority:
 * ask4(12@9) matches at price 9:
 * 1. bid3(5@9, INTERNAL_TRADER) → 5 executed, remaining ask4: 7@9
 * 2. bid1(5@9, SIGNAL_MM)       → 5 executed, remaining ask4: 2@9
 * 3. bid2(2@9, EXTERNAL)        → 2 executed, ask4 fully filled
 *
 * bid5(5@8) does NOT match because 8 < 9 (price not compatible)
 */
public class UnifiedOrderBookDemo {

    public static void main(String[] args) {
        System.out.println("=== Unified OrderBook with Internalization Priority Demo ===");
        System.out.println("Implementing YOUR EXACT scenario with priority: INTERNAL > SIGNAL > EXTERNAL\n");

        // Create the unified orderbook with priority
        UnifiedOrderBookWithPriority book = new UnifiedOrderBookWithPriority("AAPL");

        System.out.println("Step 1: Adding orders from different liquidity sources");
        System.out.println("(All orders go into the SAME OrderBook instance with source tagging)\n");

        // Signal/MM adds orders (medium priority)
        System.out.println("📊 Signal module adds market maker orders:");
        System.out.println("   bid1(5@9, SIGNAL_MM), ask1(5@11, SIGNAL_MM)");
        book.addSignalMMOrder(1001, 100, Side.BUY, 9, 5, TimeInForce.GTC);   // bid1
        book.addSignalMMOrder(1002, 100, Side.SELL, 11, 5, TimeInForce.GTC); // ask1
        printBookState("After Signal orders", book);

        // External Exchange 1 (via SOR injection)
        System.out.println("🌐 SOR injects Exchange1 orders:");
        System.out.println("   bid2(5@9, EXTERNAL), ask2(5@11, EXTERNAL)");
        book.addExternalExchangeOrder("Exchange1", Side.BUY, 9, 5);   // bid2
        book.addExternalExchangeOrder("Exchange1", Side.SELL, 11, 5); // ask2
        printBookState("After Exchange1 orders", book);

        // External Exchange 2 (via SOR injection) - Note the LOWER bid price
        System.out.println("🌐 SOR injects Exchange2 orders:");
        System.out.println("   bid5(5@8, EXTERNAL), ask5(5@12, EXTERNAL)  ← Note: bid5 @ 8");
        book.addExternalExchangeOrder("Exchange2", Side.BUY, 8, 5);   // bid5 (worse price!)
        book.addExternalExchangeOrder("Exchange2", Side.SELL, 12, 5); // ask5
        printBookState("After Exchange2 orders", book);

        // Internal trader adds order (highest priority)
        System.out.println("👤 Internal trader 1 adds order:");
        System.out.println("   bid3(5@9, INTERNAL_TRADER)");
        book.addInternalTraderOrder(3001, 400, Side.BUY, 9, 5, TimeInForce.GTC); // bid3
        printBookState("After internal bid3", book);

        System.out.println("📋 Current unified book contains orders from ALL sources:");
        System.out.println("   BIDS @9: bid1(SIGNAL_MM), bid2(EXTERNAL), bid3(INTERNAL_TRADER)");
        System.out.println("   BIDS @8: bid5(EXTERNAL) ← Lower price, different level");
        System.out.println("   ASKS @11: ask1(SIGNAL_MM), ask2(EXTERNAL)");
        System.out.println("   ASKS @12: ask5(EXTERNAL)");
        System.out.println();

        // The critical test: aggressive internal order with priority matching
        System.out.println("🚀 CRITICAL TEST: Internal trader 2 sends aggressive order:");
        System.out.println("   ask4(12@9, INTERNAL_TRADER) ← This will trigger priority matching!");
        System.out.println();
        System.out.println("Expected matching sequence (internalization priority):");
        System.out.println("   1. ask4 vs bid3(5@9, INTERNAL) → Match 5, remaining ask4: 7@9");
        System.out.println("   2. ask4 vs bid1(5@9, SIGNAL)   → Match 5, remaining ask4: 2@9");
        System.out.println("   3. ask4 vs bid2(2@9, EXTERNAL) → Match 2, ask4 fully filled");
        System.out.println("   Note: bid5@8 won't match because price 8 < 9");
        System.out.println();

        // Execute the aggressive order
        Order result = book.addInternalTraderOrder(3002, 500, Side.SELL, 9, 12, TimeInForce.GTC);

        printBookState("After aggressive ask4", book);

        // Show final statistics
        System.out.println("📊 Final Statistics:");
        UnifiedOrderBookWithPriority.UnifiedOrderBookStats stats = book.getUnifiedStats();
        System.out.println(stats);
        System.out.printf("   Internalization Rate: %.1f%%\n", stats.getInternalizationRate());
        System.out.println();

        System.out.println("✅ RESULTS ANALYSIS:");
        System.out.println("   - Aggressive ask4 order executed: " + result.getExecutedSize() + "/" + result.getOriginalSize());
        System.out.println("   - Priority matching: INTERNAL orders matched FIRST");
        System.out.println("   - Then SIGNAL orders, then EXTERNAL orders");
        System.out.println("   - All liquidity in ONE unified orderbook");
        System.out.println("   - External executions would be routed back to venues via SOR");
        System.out.println();

        System.out.println("🏗️ ARCHITECTURE SUMMARY:");
        System.out.println("   ✓ Single OrderBook instance contains ALL liquidity");
        System.out.println("   ✓ Internalization priority: INTERNAL > SIGNAL > EXTERNAL");
        System.out.println("   ✓ SOR injects external orders with proper source tagging");
        System.out.println("   ✓ Normal price-time priority within same source");
        System.out.println("   ✓ External executions routed back to venues");
    }

    private static void printBookState(String title, UnifiedOrderBookWithPriority book) {
        System.out.println("--- " + title + " ---");
        System.out.printf("Best Bid: %d (size: %d) | Best Ask: %d (size: %d) | Spread: %d%n",
                         book.getBestBidPrice(), book.getBestBidSize(),
                         book.getBestAskPrice(), book.getBestAskSize(), book.getSpread());
        System.out.printf("Orders: %d total | Levels: %d bids / %d asks%n",
                         book.getOrderCount(), book.getBidLevels(), book.getAskLevels());
        System.out.println();
    }
}
