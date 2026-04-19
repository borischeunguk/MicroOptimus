# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Rust implementation of performance-critical components from the MicroOptimus trading system. This is an early-stage port — currently only `hello_world` exists as a starter crate. The `algo/`, `signal/`, and `sor/` directories are empty placeholders for future modules mirroring the Java architecture.

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
