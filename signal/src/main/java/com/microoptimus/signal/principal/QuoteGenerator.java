package com.microoptimus.signal.principal;

import com.microoptimus.common.types.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * QuoteGenerator - Generates bid/ask quotes from unified book view
 *
 * Flow:
 * 1. Recombinor aggregates market data → Unified Book view
 * 2. Signal reads unified book (internal + external liquidity)
 * 3. QuoteGenerator creates bid/ask quotes
 * 4. PrincipalRiskController validates
 * 5. Quotes → OSM (SOR) → routes to venues
 */
public class QuoteGenerator {

    private static final Logger log = LoggerFactory.getLogger(QuoteGenerator.class);

    private final PrincipalTradingBook tradingBook;
    private final PrincipalRiskController riskController;

    // Quote parameters
    private long baseSpreadTicks = 2;
    private long baseQuoteSize = 100;
    private double skewFactor = 0.5; // How much to adjust price based on inventory

    // Quote callback
    private Consumer<Quote> quoteCallback;

    // ID generator
    private long nextQuoteId = 1;

    // Statistics
    private long quotesGenerated;
    private long quotesRejected;

    public QuoteGenerator(PrincipalTradingBook tradingBook, PrincipalRiskController riskController) {
        this.tradingBook = tradingBook;
        this.riskController = riskController;
    }

    /**
     * Set callback for generated quotes
     */
    public void setQuoteCallback(Consumer<Quote> callback) {
        this.quoteCallback = callback;
    }

    /**
     * Generate quotes based on unified book view
     */
    public void generateQuotes(int symbolIndex, long bestBid, long bestAsk,
                                long bidQty, long askQty, long timestamp) {
        if (bestBid <= 0 || bestAsk <= 0) {
            return; // Invalid market data
        }

        // Calculate mid price
        long midPrice = (bestBid + bestAsk) / 2;
        long marketSpread = bestAsk - bestBid;

        // Get inventory-adjusted spread
        long adjustedSpread = riskController.getAdjustedSpread(symbolIndex, baseSpreadTicks);

        // Use wider of market spread and our minimum spread
        long quoteSpread = Math.max(adjustedSpread, marketSpread);

        // Calculate inventory skew
        double skew = riskController.getInventorySkew(symbolIndex);

        // Adjust prices based on inventory
        // Positive skew (want to buy) -> lower bid, lower ask
        // Negative skew (want to sell) -> higher bid, higher ask
        long skewAdjustment = (long) (skew * skewFactor * quoteSpread);

        long quoteBid = midPrice - quoteSpread / 2 + skewAdjustment;
        long quoteAsk = midPrice + quoteSpread / 2 + skewAdjustment;

        // Generate bid quote
        Quote bidQuote = createQuote(symbolIndex, Side.BUY, quoteBid, baseQuoteSize, timestamp);
        if (bidQuote != null) {
            publishQuote(bidQuote);
        }

        // Generate ask quote
        Quote askQuote = createQuote(symbolIndex, Side.SELL, quoteAsk, baseQuoteSize, timestamp);
        if (askQuote != null) {
            publishQuote(askQuote);
        }
    }

    /**
     * Generate quotes with market data depth consideration
     */
    public void generateQuotesWithDepth(int symbolIndex, long[] bidPrices, long[] bidQtys,
                                         long[] askPrices, long[] askQtys, long timestamp) {
        if (bidPrices.length == 0 || askPrices.length == 0) {
            return;
        }

        // Analyze market depth
        long totalBidQty = 0;
        long totalAskQty = 0;
        for (long qty : bidQtys) totalBidQty += qty;
        for (long qty : askQtys) totalAskQty += qty;

        // Adjust quote size based on market depth
        long adjustedSize = baseQuoteSize;
        if (totalBidQty > 0 && totalAskQty > 0) {
            double depthRatio = Math.min(totalBidQty, totalAskQty) / (double) baseQuoteSize;
            if (depthRatio < 10) {
                adjustedSize = (long) (baseQuoteSize * 0.5); // Reduce size in thin market
            }
        }

        // Generate with adjusted size
        generateQuotes(symbolIndex, bidPrices[0], askPrices[0], bidQtys[0], askQtys[0], timestamp);
    }

    /**
     * Create a quote after risk checks
     */
    private Quote createQuote(int symbolIndex, Side side, long price, long quantity, long timestamp) {
        // Risk check
        PrincipalRiskController.RiskCheckResult result =
                riskController.checkQuote(symbolIndex, side, price, quantity);

        if (!result.isApproved()) {
            quotesRejected++;
            log.debug("Quote rejected: {}", result.getRejectReason());
            return null;
        }

        Quote quote = new Quote();
        quote.quoteId = nextQuoteId++;
        quote.symbolIndex = symbolIndex;
        quote.side = side;
        quote.price = price;
        quote.quantity = quantity;
        quote.timestamp = timestamp;

        quotesGenerated++;
        return quote;
    }

    /**
     * Publish quote to callback
     */
    private void publishQuote(Quote quote) {
        if (quoteCallback != null) {
            quoteCallback.accept(quote);
        }
        log.debug("Generated quote: {}", quote);
    }

    // Configuration
    public void setBaseSpreadTicks(long spread) { this.baseSpreadTicks = spread; }
    public void setBaseQuoteSize(long size) { this.baseQuoteSize = size; }
    public void setSkewFactor(double factor) { this.skewFactor = factor; }

    // Statistics
    public long getQuotesGenerated() { return quotesGenerated; }
    public long getQuotesRejected() { return quotesRejected; }

    /**
     * Quote - Generated quote to send to SOR
     */
    public static class Quote {
        public long quoteId;
        public int symbolIndex;
        public Side side;
        public long price;
        public long quantity;
        public long timestamp;

        @Override
        public String toString() {
            return String.format("Quote{id=%d, symbol=%d, %s %d@%d}",
                    quoteId, symbolIndex, side, quantity, price);
        }
    }
}
