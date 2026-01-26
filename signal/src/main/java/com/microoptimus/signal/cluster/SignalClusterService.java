package com.microoptimus.signal.cluster;

import com.microoptimus.common.types.Side;
import com.microoptimus.signal.principal.PrincipalTradingBook;
import com.microoptimus.signal.principal.PrincipalRiskController;
import com.microoptimus.signal.principal.QuoteGenerator;
import com.microoptimus.signal.strategy.InventoryManager;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SignalClusterService - Aeron Cluster service for market making / principal trading
 *
 * Responsibilities:
 * - Receives unified book updates from recombinor
 * - Generates principal quotes
 * - Manages inventory and risk
 * - Routes quotes to OSM (SOR)
 */
public class SignalClusterService implements ClusteredService {

    private static final Logger log = LoggerFactory.getLogger(SignalClusterService.class);

    // Core components
    private final PrincipalTradingBook tradingBook;
    private final PrincipalRiskController riskController;
    private final QuoteGenerator quoteGenerator;
    private final InventoryManager inventoryManager;

    // Response buffer
    private final MutableDirectBuffer responseBuffer;

    // Cluster state
    private Cluster cluster;
    private long lastSequenceNumber;

    // Processing interval
    private static final long QUOTE_INTERVAL_NS = 100_000_000; // 100ms
    private long lastQuoteTime;

    public SignalClusterService() {
        this.tradingBook = new PrincipalTradingBook();
        this.riskController = new PrincipalRiskController(tradingBook);
        this.quoteGenerator = new QuoteGenerator(tradingBook, riskController);
        this.inventoryManager = new InventoryManager(tradingBook);
        this.responseBuffer = new ExpandableDirectByteBuffer(512);

        // Wire up quote callback
        quoteGenerator.setQuoteCallback(this::onQuoteGenerated);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        log.info("Signal Cluster Service started, role={}", cluster.role());

        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.debug("Session opened: sessionId={}", session.id());
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.debug("Session closed: sessionId={}, reason={}", session.id(), closeReason);
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp,
                                  DirectBuffer buffer, int offset, int length,
                                  Header header) {
        int messageType = buffer.getInt(offset);
        offset += 4;

        switch (messageType) {
            case 1: // UnifiedBookUpdate
                handleUnifiedBookUpdate(timestamp, buffer, offset);
                break;
            case 2: // ExecutionReport (from SOR)
                handleExecutionReport(timestamp, buffer, offset);
                break;
            case 3: // RiskCommand
                handleRiskCommand(session, buffer, offset);
                break;
            default:
                log.warn("Unknown message type: {}", messageType);
        }

        lastSequenceNumber = header.position();
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Decay inventory targets
        inventoryManager.decayTargets(timestamp);

        // Check if hedging is needed
        checkHedgingNeeds(timestamp);
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        log.info("Taking signal snapshot...");
        // Snapshot trading book and positions
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("Signal role changed to: {}", newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("Signal Cluster Service terminating");
    }

    /**
     * Handle unified book update from recombinor
     */
    private void handleUnifiedBookUpdate(long timestamp, DirectBuffer buffer, int offset) {
        // Decode (simplified - would use SBE)
        long sequenceId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset); offset += 4;
        long bestBid = buffer.getLong(offset); offset += 8;
        long bestBidQty = buffer.getLong(offset); offset += 8;
        long bestAsk = buffer.getLong(offset); offset += 8;
        long bestAskQty = buffer.getLong(offset);

        // Update mark-to-market
        tradingBook.updateMarkToMarket(symbolIndex, bestBid, bestAsk);

        // Generate quotes if interval has elapsed
        if (timestamp - lastQuoteTime >= QUOTE_INTERVAL_NS) {
            quoteGenerator.generateQuotes(symbolIndex, bestBid, bestAsk,
                    bestBidQty, bestAskQty, timestamp);
            lastQuoteTime = timestamp;
        }
    }

    /**
     * Handle execution report from SOR
     */
    private void handleExecutionReport(long timestamp, DirectBuffer buffer, int offset) {
        long orderId = buffer.getLong(offset); offset += 8;
        int symbolIndex = buffer.getInt(offset); offset += 4;
        int sideOrdinal = buffer.getInt(offset); offset += 4;
        long execQty = buffer.getLong(offset); offset += 8;
        long execPrice = buffer.getLong(offset);

        // Update trading book
        Side side = Side.values()[sideOrdinal];
        tradingBook.onTrade(symbolIndex, side, execQty, execPrice, timestamp);

        log.debug("Execution: symbol={}, {} {}@{}, pnl={}",
                symbolIndex, side, execQty, execPrice, tradingBook.getTotalPnL());
    }

    /**
     * Handle risk control command
     */
    private void handleRiskCommand(ClientSession session, DirectBuffer buffer, int offset) {
        int commandType = buffer.getInt(offset);

        switch (commandType) {
            case 0: // Enable trading
                riskController.enableTrading();
                break;
            case 1: // Disable trading
                riskController.disableTrading("Manual disable");
                break;
            case 2: // Flatten positions
                // Would cancel all quotes and hedge to flat
                break;
        }
    }

    /**
     * Callback when quote is generated
     */
    private void onQuoteGenerated(QuoteGenerator.Quote quote) {
        // Encode and send to SOR (via cluster)
        int offset = 0;
        responseBuffer.putInt(offset, 10); // QuoteToSOR message type
        offset += 4;
        responseBuffer.putLong(offset, quote.quoteId);
        offset += 8;
        responseBuffer.putInt(offset, quote.symbolIndex);
        offset += 4;
        responseBuffer.putInt(offset, quote.side.ordinal());
        offset += 4;
        responseBuffer.putLong(offset, quote.price);
        offset += 8;
        responseBuffer.putLong(offset, quote.quantity);
        offset += 8;
        responseBuffer.putLong(offset, quote.timestamp);

        // Would send via cluster ingress
        log.debug("Quote sent to SOR: {}", quote);
    }

    /**
     * Check if any symbols need active hedging
     */
    private void checkHedgingNeeds(long timestamp) {
        // This would iterate over all symbols and generate hedge orders if needed
        // For now, just log warning if any symbol needs hedging
    }

    /**
     * Load snapshot for recovery
     */
    private void loadSnapshot(Image snapshotImage) {
        log.info("Loading signal snapshot...");
    }

    // Accessors
    public PrincipalTradingBook getTradingBook() { return tradingBook; }
    public PrincipalRiskController getRiskController() { return riskController; }
    public QuoteGenerator getQuoteGenerator() { return quoteGenerator; }
    public InventoryManager getInventoryManager() { return inventoryManager; }
    public long getLastSequenceNumber() { return lastSequenceNumber; }
}
