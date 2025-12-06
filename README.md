# MicroOptimus

Objective:

Design and implement Sequencer ( Coral C++, Lmax Disruptor or Aeron Java ) based orderbook and matching engine ( java ), market data processer (java),
order gateway ( c++ ), and market data gateway ( c++ ), market making ( java ) 

lock free, gc free, zero copy, shared memory based high performance system for trading.

Sequencer can start from lmax disruptor, though it should be interfaced to allow swapping to Coral Ring or Aeron Cluster later.

Use CME Exchange as example for implementation.

Both FIX and SBE protocols should be supported at order gateway and market data gateway, with SBE preferred for low latency.

Versions:

MVP using lmax disruptor: components all communicate via shared memory, single process for all components, single threaded orderbook and matching engine, single threaded market data processor, single threaded market data gateway, single threaded order gateway.

Standard Java Version using Aeron Cluster: support multi processes, multi nodes deployment

Advanced Version using Coral Blocks C++ : lock free, gc free, zero copy, shared memory based high performance system for trading.

Benchmark: 

Ideally:
latency < 500 nano seconds end to end from order entry to order ack, 
throughput > 30 million orders per second

Acceptable:
latency < 2 micro seconds end to end from order entry to order ack, 
throughput > 10 million orders per second

Reference:

lmax disruptor: https://github.com/LMAX-Exchange/disruptor

Aeron: https://github.com/aeron-io

Coral Blocks: https://github.com/coralblocks
# Coral Me:https://github.com/coralblocks/CoralME
# Coral Ring: https://github.com/coralblocks/CoralRing
# Coral Queue: https://github.com/coralblocks/CoralQueue
# Coral Pool: https://github.com/coralblocks/CoralPool





