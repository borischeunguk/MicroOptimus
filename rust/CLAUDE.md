# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

MVP / POC version of the Rust module for MicroOptimus. This Rust code will only implement core components of the trading system. 
signals focus on quantitative mathematical calculations for algos and sor strategies
algo focus on executions algorithms, currently only support VWAP algo which takes params from signals module, slices parent orders to child orders 
sor focus on order routing logic,which takes child orders from algo module and routes to different venues based on market conditions and order parameters. 
The Rust module will use Aeron Sequencer ( UDP Multicast ) for IPC, SBE and shared memory ( memory mapped files) architecture. 
The primary goal is to implement the Smart Order Router (SOR) and algorithmic execution strategies in rust, with a focus on ultra-low latency and high throughput.

## Build Commands

```bash
# Build (from a crate directory, e.g. rust/hello_world)
cargo build
cargo build --release

# Run
cargo run

# Run all tests
cargo test

# Run a single test
cargo test test_name

# Lint
cargo clippy

# Format
cargo fmt
```

## Project Structure

There is no Cargo workspace — each subdirectory is (or will be) an independent crate:

- **hello_world/** — Starter crate (Rust 2021 edition, no dependencies)
- **sor/** — Smart Order Router (planned, mirrors Java/C++ `liquidator` module)
- **algo/** — Algorithmic execution: VWAP, TWAP, Iceberg (planned, mirrors Java `algo` module)
- **signal/** — Principal trading / market-making (planned, mirrors Java `signal` module)

## Constraints

This Rust code must follow the same performance principles as the parent project:

- **Zero allocation in hot paths** — use object pooling, stack allocation, or arena allocators
- **Lock-free data structures** — prefer `crossbeam`, `parking_lot`, or atomic primitives
- **Zero-copy** — use shared memory and binary encoding (SBE-compatible) for IPC with Java/C++ components
- **Target latencies** — sub-500ns for matching and routing operations
