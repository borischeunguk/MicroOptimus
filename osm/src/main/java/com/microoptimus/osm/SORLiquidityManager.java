package com.microoptimus.osm;

import com.microoptimus.common.types.Side;
import com.microoptimus.liquidator.sor.SmartOrderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SORLiquidityManager - Integration component showing how SOR fits with UnifiedOrderBook
 *
 * SOR's Role in Your Architecture:
 * 1. LIQUIDITY GATEWAY: Monitors external venues (CME, NASDAQ, etc.)
 * 2. ORDER INJECTION: Injects external quotes as tagged orders into unified book
 * 3. EXECUTION ROUTING: Routes executions back to appropriate external venues
 * 4. RISK MANAGEMENT: Filters/validates external orders before injection
 *
 * Two Implementation Options:
 * A) Java SOR (this file) - easier integration, ~1μs latency
 * B) C++ SOR in liquidator module - ultra-fast, ~500ns latency
 */
public class SORLiquidityManager {

    private static final Logger log = LoggerFactory.getLogger(SORLiquidityManager.class);

    private final UnifiedOrderBookWithPriority unifiedBook;
    private final SmartOrderRouter sor;
    private final ScheduledExecutorService scheduler;

    // External venue tracking
    private final AtomicLong cmeOrdersInjected = new AtomicLong(0);
    private final AtomicLong nasdaqOrdersInjected = new AtomicLong(0);
    private final AtomicLong nyseOrdersInjected = new AtomicLong(0);
    private final AtomicLong totalExecutionsRouted = new AtomicLong(0);

    // Configuration
    private final long injectionIntervalMs;
    private final boolean enabled;

    public SORLiquidityManager(UnifiedOrderBookWithPriority unifiedBook,
                              long injectionIntervalMs, boolean enabled) {
        this.unifiedBook = unifiedBook;
        this.injectionIntervalMs = injectionIntervalMs;
        this.enabled = enabled;

        // Make SOR optional
        SmartOrderRouter tempSOR;
        try {
            tempSOR = new SmartOrderRouter();
        } catch (Exception e) {
            log.warn("SmartOrderRouter not available, SOR features disabled: {}", e.getMessage());
            tempSOR = null;
        }
        this.sor = tempSOR;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SOR-Liquidity-Manager");
            t.setDaemon(true);
            return t;
        });

        log.info("SORLiquidityManager initialized for '{}' - injection interval: {}ms, enabled: {}",
                unifiedBook.getSymbol(), injectionIntervalMs, enabled);
    }

    /**
     * Start the SOR liquidity injection process
     */
    public void start() {
        if (!enabled) {
            log.info("SOR liquidity injection is DISABLED");
            return;
        }

        log.info("Starting SOR liquidity injection every {}ms", injectionIntervalMs);

        scheduler.scheduleAtFixedRate(
            this::injectExternalLiquidity,
            0, // Initial delay
            injectionIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stop the SOR liquidity injection process
     */
    public void stop() {
        log.info("Stopping SOR liquidity injection");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * CORE SOR FUNCTION: Inject external venue liquidity into unified book
     *
     * This is where SOR monitors external venues and injects their quotes
     * as EXTERNAL_EXCHANGE orders into the unified orderbook
     */
    private void injectExternalLiquidity() {
        try {
            String symbol = unifiedBook.getSymbol();
            long startTime = System.nanoTime();

            // Poll CME for best quotes
            injectCMELiquidity(symbol);

            // Poll NASDAQ for best quotes
            injectNASDAQLiquidity(symbol);

            // Poll NYSE for best quotes
            injectNYSELiquidity(symbol);

            long latency = System.nanoTime() - startTime;
            if (latency > 10_000) { // Log if > 10μs
                log.debug("SOR injection took {}μs for {}", latency / 1000, symbol);
            }

        } catch (Exception e) {
            log.error("Failed to inject external liquidity: {}", e.getMessage(), e);
        }
    }

    /**
     * Inject CME liquidity (futures/options)
     */
    private void injectCMELiquidity(String symbol) {
        try {
            // In real implementation, this would query CME iLink3 API
            ExternalQuote cmeBestBid = getCMEBestBid(symbol);
            ExternalQuote cmeBestAsk = getCMEBestAsk(symbol);

            if (cmeBestBid != null && cmeBestBid.isValid()) {
                unifiedBook.addExternalExchangeOrder("CME", Side.BUY,
                                                    cmeBestBid.price, cmeBestBid.size);
                cmeOrdersInjected.incrementAndGet();

                log.trace("Injected CME bid: {} @ {}", cmeBestBid.size, cmeBestBid.price);
            }

            if (cmeBestAsk != null && cmeBestAsk.isValid()) {
                unifiedBook.addExternalExchangeOrder("CME", Side.SELL,
                                                    cmeBestAsk.price, cmeBestAsk.size);
                cmeOrdersInjected.incrementAndGet();

                log.trace("Injected CME ask: {} @ {}", cmeBestAsk.size, cmeBestAsk.price);
            }

        } catch (Exception e) {
            log.warn("Failed to inject CME liquidity: {}", e.getMessage());
        }
    }

    /**
     * Inject NASDAQ liquidity (equities)
     */
    private void injectNASDAQLiquidity(String symbol) {
        try {
            // In real implementation, this would query NASDAQ OUCH API
            ExternalQuote nasdaqBestBid = getNASDAQBestBid(symbol);
            ExternalQuote nasdaqBestAsk = getNASDAQBestAsk(symbol);

            if (nasdaqBestBid != null && nasdaqBestBid.isValid()) {
                unifiedBook.addExternalExchangeOrder("NASDAQ", Side.BUY,
                                                    nasdaqBestBid.price, nasdaqBestBid.size);
                nasdaqOrdersInjected.incrementAndGet();
            }

            if (nasdaqBestAsk != null && nasdaqBestAsk.isValid()) {
                unifiedBook.addExternalExchangeOrder("NASDAQ", Side.SELL,
                                                    nasdaqBestAsk.price, nasdaqBestAsk.size);
                nasdaqOrdersInjected.incrementAndGet();
            }

        } catch (Exception e) {
            log.warn("Failed to inject NASDAQ liquidity: {}", e.getMessage());
        }
    }

    /**
     * Inject NYSE liquidity (equities)
     */
    private void injectNYSELiquidity(String symbol) {
        try {
            // In real implementation, this would query NYSE FIX API
            ExternalQuote nyseBestBid = getNYSEBestBid(symbol);
            ExternalQuote nyseBestAsk = getNYSEBestAsk(symbol);

            if (nyseBestBid != null && nyseBestBid.isValid()) {
                unifiedBook.addExternalExchangeOrder("NYSE", Side.BUY,
                                                    nyseBestBid.price, nyseBestBid.size);
                nyseOrdersInjected.incrementAndGet();
            }

            if (nyseBestAsk != null && nyseBestAsk.isValid()) {
                unifiedBook.addExternalExchangeOrder("NYSE", Side.SELL,
                                                    nyseBestAsk.price, nyseBestAsk.size);
                nyseOrdersInjected.incrementAndGet();
            }

        } catch (Exception e) {
            log.warn("Failed to inject NYSE liquidity: {}", e.getMessage());
        }
    }

    /**
     * Handle execution report - route back to external venue
     * This would be called when an external order gets matched in our unified book
     */
    public void onExternalOrderExecution(Order externalOrder, long quantity, long price) {
        try {
            String exchangeName = getExchangeFromOrder(externalOrder);
            totalExecutionsRouted.incrementAndGet();

            log.debug("Routing execution to {}: order {} executed {} @ {}",
                     exchangeName, externalOrder.getOrderId(), quantity, price);

            // Route execution back to appropriate venue
            switch (exchangeName) {
                case "CME":
                    routeToCME(externalOrder.getOrderId(), quantity, price);
                    break;
                case "NASDAQ":
                    routeToNASDAQ(externalOrder.getOrderId(), quantity, price);
                    break;
                case "NYSE":
                    routeToNYSE(externalOrder.getOrderId(), quantity, price);
                    break;
                default:
                    log.warn("Unknown exchange for execution routing: {}", exchangeName);
            }

        } catch (Exception e) {
            log.error("Failed to route execution: {}", e.getMessage(), e);
        }
    }

    // Mock implementations of external venue APIs (replace with real APIs)

    private ExternalQuote getCMEBestBid(String symbol) {
        // Mock: return random quote 20% of the time
        if (Math.random() > 0.8) {
            return new ExternalQuote(Side.BUY, 950, 100, "CME");
        }
        return null;
    }

    private ExternalQuote getCMEBestAsk(String symbol) {
        if (Math.random() > 0.8) {
            return new ExternalQuote(Side.SELL, 1050, 100, "CME");
        }
        return null;
    }

    private ExternalQuote getNASDAQBestBid(String symbol) {
        if (Math.random() > 0.9) {
            return new ExternalQuote(Side.BUY, 949, 75, "NASDAQ");
        }
        return null;
    }

    private ExternalQuote getNASDAQBestAsk(String symbol) {
        if (Math.random() > 0.9) {
            return new ExternalQuote(Side.SELL, 1051, 75, "NASDAQ");
        }
        return null;
    }

    private ExternalQuote getNYSEBestBid(String symbol) {
        if (Math.random() > 0.95) {
            return new ExternalQuote(Side.BUY, 948, 200, "NYSE");
        }
        return null;
    }

    private ExternalQuote getNYSEBestAsk(String symbol) {
        if (Math.random() > 0.95) {
            return new ExternalQuote(Side.SELL, 1052, 200, "NYSE");
        }
        return null;
    }

    private void routeToCME(long orderId, long quantity, long price) {
        // In real implementation: send iLink3 execution report
        log.debug("CME execution routed: order {} → {} @ {}", orderId, quantity, price);
    }

    private void routeToNASDAQ(long orderId, long quantity, long price) {
        // In real implementation: send OUCH execution report
        log.debug("NASDAQ execution routed: order {} → {} @ {}", orderId, quantity, price);
    }

    private void routeToNYSE(long orderId, long quantity, long price) {
        // In real implementation: send FIX execution report
        log.debug("NYSE execution routed: order {} → {} @ {}", orderId, quantity, price);
    }

    private String getExchangeFromOrder(Order order) {
        // Extract exchange name from client ID (reverse lookup)
        long clientId = order.getClientId();
        if (clientId == Math.abs("CME".hashCode())) return "CME";
        if (clientId == Math.abs("NASDAQ".hashCode())) return "NASDAQ";
        if (clientId == Math.abs("NYSE".hashCode())) return "NYSE";
        return "UNKNOWN";
    }

    // Statistics
    public SORStats getStats() {
        return new SORStats(
            cmeOrdersInjected.get(),
            nasdaqOrdersInjected.get(),
            nyseOrdersInjected.get(),
            totalExecutionsRouted.get(),
            enabled
        );
    }

    /**
     * External quote from venue API
     */
    public static class ExternalQuote {
        public final Side side;
        public final long price;
        public final long size;
        public final String venue;
        public final long timestamp;

        public ExternalQuote(Side side, long price, long size, String venue) {
            this.side = side;
            this.price = price;
            this.size = size;
            this.venue = venue;
            this.timestamp = System.nanoTime();
        }

        public boolean isValid() {
            return price > 0 && size > 0 &&
                   (System.nanoTime() - timestamp) < 5_000_000_000L; // 5 second validity
        }
    }

    /**
     * SOR statistics
     */
    public static class SORStats {
        public final long cmeOrdersInjected;
        public final long nasdaqOrdersInjected;
        public final long nyseOrdersInjected;
        public final long totalExecutionsRouted;
        public final boolean enabled;

        public SORStats(long cmeOrders, long nasdaqOrders, long nyseOrders,
                       long executions, boolean enabled) {
            this.cmeOrdersInjected = cmeOrders;
            this.nasdaqOrdersInjected = nasdaqOrders;
            this.nyseOrdersInjected = nyseOrders;
            this.totalExecutionsRouted = executions;
            this.enabled = enabled;
        }

        public long getTotalOrdersInjected() {
            return cmeOrdersInjected + nasdaqOrdersInjected + nyseOrdersInjected;
        }

        @Override
        public String toString() {
            return String.format("SORStats{injected[CME=%d,NASDAQ=%d,NYSE=%d,total=%d], " +
                               "routed=%d, enabled=%s}",
                               cmeOrdersInjected, nasdaqOrdersInjected, nyseOrdersInjected,
                               getTotalOrdersInjected(), totalExecutionsRouted, enabled);
        }
    }
}
