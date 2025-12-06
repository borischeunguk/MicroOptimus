#!/usr/bin/env python3
"""
Demo script for Market Maker strategy
"""

import time
import logging
from marketmaker.market_maker import MarketMaker, MarketData, OrderSide

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def main():
    print("=== MicroOptimus Market Maker Demo ===\n")

    # Initialize market maker
    symbol = "BTCUSD"
    mm = MarketMaker(
        symbol=symbol,
        spread_bps=10.0,  # 10 basis points spread
        quote_size=10,     # Quote 10 units
        max_position=100,  # Max position of 100 units
        max_loss=10000.0,  # Max loss of $10,000
        skew_factor=0.1    # 10% inventory skewing
    )

    print("\n=== Scenario 1: Initial Quote ===")

    # Simulate initial market data
    market_data = MarketData(
        symbol=symbol,
        bid_price=50000.0,
        bid_size=100,
        ask_price=50010.0,
        ask_size=100,
        last_price=50005.0,
        timestamp=time.time()
    )

    print(f"Market: Bid={market_data.bid_price:.2f} Ask={market_data.ask_price:.2f} "
          f"Mid={market_data.mid_price:.2f} Spread={market_data.spread:.2f}")

    # Generate initial quote
    mm.on_market_data(market_data)

    print("\n=== Scenario 2: Simulating Fills - Building Long Position ===")

    # Simulate buy fills - building a long position
    for i in range(3):
        fill_price = 50000.0 - i * 5.0
        fill_qty = 10
        print(f"\nFill {i+1}: BUY {fill_qty} @ {fill_price:.2f}")
        mm.on_fill(f"order_{i}", OrderSide.BUY, fill_price, fill_qty)

        position = mm.get_position_info()
        print(f"Position: {position.quantity}, Avg Price: {position.average_price:.2f}")

        # Update market data and generate new quote
        market_data = MarketData(
            symbol=symbol,
            bid_price=50000.0,
            bid_size=100,
            ask_price=50010.0,
            ask_size=100,
            last_price=50005.0,
            timestamp=time.time()
        )
        mm.on_market_data(market_data)

    print("\n=== Scenario 3: Simulating Fills - Reducing Position ===")

    # Simulate sell fills - reducing position
    for i in range(2):
        fill_price = 50015.0 + i * 5.0
        fill_qty = 10
        print(f"\nFill {i+4}: SELL {fill_qty} @ {fill_price:.2f}")
        mm.on_fill(f"order_{i+3}", OrderSide.SELL, fill_price, fill_qty)

        position = mm.get_position_info()
        print(f"Position: {position.quantity}, Avg Price: {position.average_price:.2f}")

        # Update market data and generate new quote
        market_data = MarketData(
            symbol=symbol,
            bid_price=50010.0,
            bid_size=100,
            ask_price=50020.0,
            ask_size=100,
            last_price=50015.0,
            timestamp=time.time()
        )
        mm.on_market_data(market_data)

    print("\n=== Scenario 4: Market Movement with Position ===")

    # Simulate market moving up while we have a position
    prices = [50020.0, 50030.0, 50040.0, 50050.0]
    for i, base_price in enumerate(prices):
        print(f"\nMarket Update {i+1}: Mid Price = {base_price:.2f}")
        market_data = MarketData(
            symbol=symbol,
            bid_price=base_price - 5.0,
            bid_size=100,
            ask_price=base_price + 5.0,
            ask_size=100,
            last_price=base_price,
            timestamp=time.time()
        )
        mm.on_market_data(market_data)

        position = mm.get_position_info()
        print(f"Position: {position.quantity}, Unrealized PnL: ${position.unrealized_pnl:.2f}")

    print("\n=== Final Statistics ===")
    stats = mm.get_statistics()
    print(f"Symbol: {stats['symbol']}")
    print(f"Position: {stats['position']}")
    print(f"Average Price: ${stats['average_price']:.2f}")
    print(f"Realized P&L: ${stats['realized_pnl']:.2f}")
    print(f"Unrealized P&L: ${stats['unrealized_pnl']:.2f}")
    print(f"Total P&L: ${stats['total_pnl']:.2f}")

    print("\n=== Demo Complete ===")


if __name__ == "__main__":
    main()
