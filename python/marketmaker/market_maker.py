"""
Market Making Strategy
Provides liquidity by continuously quoting bid and ask prices
"""

import time
import logging
from typing import Dict, Optional, Tuple
from dataclasses import dataclass
from enum import Enum

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class OrderSide(Enum):
    BUY = "BUY"
    SELL = "SELL"


@dataclass
class MarketData:
    """Market data snapshot"""
    symbol: str
    bid_price: float
    bid_size: int
    ask_price: float
    ask_size: int
    last_price: float
    timestamp: float

    @property
    def mid_price(self) -> float:
        if self.bid_price > 0 and self.ask_price > 0:
            return (self.bid_price + self.ask_price) / 2.0
        return 0.0

    @property
    def spread(self) -> float:
        if self.bid_price > 0 and self.ask_price > 0:
            return self.ask_price - self.bid_price
        return 0.0


@dataclass
class Position:
    """Current position in a symbol"""
    symbol: str
    quantity: int
    average_price: float
    realized_pnl: float
    unrealized_pnl: float


@dataclass
class Quote:
    """Market maker quote"""
    symbol: str
    bid_price: float
    bid_size: int
    ask_price: float
    ask_size: int


class RiskManager:
    """Manages risk limits for market making"""

    def __init__(self, max_position: int, max_loss: float):
        self.max_position = max_position
        self.max_loss = max_loss
        self.current_pnl = 0.0

    def check_position_limit(self, current_position: int, order_quantity: int, side: OrderSide) -> bool:
        """Check if order would exceed position limits"""
        if side == OrderSide.BUY:
            new_position = current_position + order_quantity
        else:
            new_position = current_position - order_quantity

        return abs(new_position) <= self.max_position

    def check_loss_limit(self) -> bool:
        """Check if loss limit is exceeded"""
        return self.current_pnl > -self.max_loss

    def update_pnl(self, pnl: float):
        """Update current P&L"""
        self.current_pnl = pnl


class InventoryManager:
    """Manages inventory and positions"""

    def __init__(self):
        self.positions: Dict[str, Position] = {}

    def get_position(self, symbol: str) -> Position:
        """Get current position for a symbol"""
        if symbol not in self.positions:
            self.positions[symbol] = Position(
                symbol=symbol,
                quantity=0,
                average_price=0.0,
                realized_pnl=0.0,
                unrealized_pnl=0.0
            )
        return self.positions[symbol]

    def _calculate_close_pnl(self, position_qty: int, avg_price: float, 
                            fill_qty: int, fill_price: float, is_buy: bool) -> float:
        """Calculate realized P&L when closing a position"""
        if is_buy:
            # Closing short position (buying back)
            return -fill_qty * (avg_price - fill_price)
        else:
            # Closing long position (selling)
            return fill_qty * (fill_price - avg_price)

    def update_position(self, symbol: str, quantity: int, price: float, side: OrderSide):
        """Update position after a trade"""
        position = self.get_position(symbol)
        is_buy = (side == OrderSide.BUY)

        if is_buy:
            # Buying increases position
            if position.quantity >= 0:
                # Adding to long position
                total_cost = position.quantity * position.average_price + quantity * price
                position.quantity += quantity
                position.average_price = total_cost / position.quantity if position.quantity > 0 else 0.0
            else:
                # Reducing short position (buying back)
                close_qty = min(quantity, abs(position.quantity))
                pnl = self._calculate_close_pnl(position.quantity, position.average_price, 
                                                close_qty, price, is_buy)
                position.realized_pnl += pnl
                
                # Update position: add the buy quantity (moves from negative toward positive)
                position.quantity += quantity
                # If we flip to long, set new average price
                if position.quantity > 0:
                    position.average_price = price
                else:
                    # Still short or flat, keep old avg price if still short
                    position.average_price = position.average_price if position.quantity < 0 else 0.0
        else:
            # Selling decreases position
            if position.quantity <= 0:
                # Adding to short position
                total_cost = abs(position.quantity) * position.average_price + quantity * price
                position.quantity -= quantity
                position.average_price = total_cost / abs(position.quantity) if position.quantity < 0 else 0.0
            else:
                # Reducing long position (selling)
                close_qty = min(quantity, position.quantity)
                pnl = self._calculate_close_pnl(position.quantity, position.average_price,
                                                close_qty, price, is_buy)
                position.realized_pnl += pnl
                
                # Update position: subtract the sell quantity (moves from positive toward negative)
                position.quantity -= quantity
                # If we flip to short, set new average price
                if position.quantity < 0:
                    position.average_price = price
                else:
                    # Still long or flat, keep old avg price if still long
                    position.average_price = position.average_price if position.quantity > 0 else 0.0

    def calculate_unrealized_pnl(self, symbol: str, current_price: float):
        """Calculate unrealized P&L"""
        position = self.get_position(symbol)
        if position.quantity == 0:
            position.unrealized_pnl = 0.0
        else:
            position.unrealized_pnl = position.quantity * (current_price - position.average_price)


class MarketMaker:
    """
    Market making strategy that provides liquidity
    """

    def __init__(
        self,
        symbol: str,
        spread_bps: float = 10.0,
        quote_size: int = 100,
        max_position: int = 1000,
        max_loss: float = 10000.0,
        skew_factor: float = 0.1
    ):
        """
        Initialize market maker

        Args:
            symbol: Trading symbol
            spread_bps: Spread in basis points (1 bp = 0.01%)
            quote_size: Size of each quote
            max_position: Maximum position size
            max_loss: Maximum allowed loss
            skew_factor: Factor for inventory skewing (0-1)
        """
        self.symbol = symbol
        self.spread_bps = spread_bps
        self.quote_size = quote_size
        self.skew_factor = skew_factor

        self.risk_manager = RiskManager(max_position, max_loss)
        self.inventory_manager = InventoryManager()

        self.active_bid_order: Optional[str] = None
        self.active_ask_order: Optional[str] = None

        logger.info(f"Initialized MarketMaker for {symbol}")

    def calculate_quote(self, market_data: MarketData) -> Optional[Quote]:
        """
        Calculate market maker quotes based on current market data

        Returns bid and ask prices with inventory skewing
        """
        if market_data.mid_price <= 0:
            logger.warning("Invalid market data - cannot quote")
            return None

        position = self.inventory_manager.get_position(self.symbol)
        mid_price = market_data.mid_price

        # Calculate half spread
        half_spread = mid_price * (self.spread_bps / 10000.0) / 2.0

        # Apply inventory skewing
        # If long, push quotes lower to encourage selling
        # If short, push quotes higher to encourage buying
        skew = 0.0
        if abs(position.quantity) > 0:
            inventory_ratio = position.quantity / self.risk_manager.max_position
            skew = -inventory_ratio * self.skew_factor * mid_price

        bid_price = mid_price - half_spread + skew
        ask_price = mid_price + half_spread + skew

        # Check risk limits
        if not self.risk_manager.check_loss_limit():
            logger.warning("Loss limit exceeded - not quoting")
            return None

        # Check position limits for bid
        bid_size = self.quote_size
        if not self.risk_manager.check_position_limit(position.quantity, bid_size, OrderSide.BUY):
            logger.info("Bid would exceed position limit")
            bid_size = 0

        # Check position limits for ask
        ask_size = self.quote_size
        if not self.risk_manager.check_position_limit(position.quantity, ask_size, OrderSide.SELL):
            logger.info("Ask would exceed position limit")
            ask_size = 0

        if bid_size == 0 and ask_size == 0:
            logger.warning("Cannot quote - position limits reached")
            return None

        return Quote(
            symbol=self.symbol,
            bid_price=round(bid_price, 2),
            bid_size=bid_size,
            ask_price=round(ask_price, 2),
            ask_size=ask_size
        )

    def on_market_data(self, market_data: MarketData):
        """Handle market data update"""
        logger.debug(f"Market data: {market_data.symbol} Mid={market_data.mid_price:.2f}")

        # Update unrealized P&L
        self.inventory_manager.calculate_unrealized_pnl(self.symbol, market_data.mid_price)

        # Calculate new quotes
        quote = self.calculate_quote(market_data)
        if quote:
            logger.info(f"Quote: {quote.symbol} Bid={quote.bid_price:.2f}({quote.bid_size}) "
                       f"Ask={quote.ask_price:.2f}({quote.ask_size})")

    def on_fill(self, order_id: str, side: OrderSide, price: float, quantity: int):
        """Handle order fill"""
        logger.info(f"Fill: {side.value} {quantity} @ {price:.2f}")

        # Update position
        self.inventory_manager.update_position(self.symbol, quantity, price, side)

        position = self.inventory_manager.get_position(self.symbol)
        total_pnl = position.realized_pnl + position.unrealized_pnl
        self.risk_manager.update_pnl(total_pnl)

        logger.info(f"Position: {position.quantity}, Realized PnL: {position.realized_pnl:.2f}, "
                   f"Unrealized PnL: {position.unrealized_pnl:.2f}")

    def get_position_info(self) -> Position:
        """Get current position information"""
        return self.inventory_manager.get_position(self.symbol)

    def get_statistics(self) -> Dict:
        """Get market making statistics"""
        position = self.inventory_manager.get_position(self.symbol)
        return {
            'symbol': self.symbol,
            'position': position.quantity,
            'average_price': position.average_price,
            'realized_pnl': position.realized_pnl,
            'unrealized_pnl': position.unrealized_pnl,
            'total_pnl': position.realized_pnl + position.unrealized_pnl,
            'current_pnl': self.risk_manager.current_pnl
        }
