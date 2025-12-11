#!/bin/bash

# Integration Test Script for Aeron Cluster Implementation
# Tests the global sequencer with shared memory architecture

set -e

echo "=== Aeron Cluster Integration Test ==="
echo "Testing: MDR → Cluster Sequencer → MM/OSM processes"
echo ""

# Configuration
PROJECT_DIR="/Users/xinyue/IdeaProjects/MicroOptimus"
CLASSPATH="$PROJECT_DIR/build/libs/*:$PROJECT_DIR/*/build/libs/*"
SHM_PATH="/tmp/md_store.bin"

# Cleanup function
cleanup() {
    echo ""
    echo "=== Cleanup ==="

    # Kill background processes
    if [[ -n "$CLUSTER_PIDS" ]]; then
        echo "Stopping cluster nodes..."
        for pid in $CLUSTER_PIDS; do
            kill $pid 2>/dev/null || true
        done
    fi

    if [[ -n "$CONSUMER_PIDS" ]]; then
        echo "Stopping consumer processes..."
        for pid in $CONSUMER_PIDS; do
            kill $pid 2>/dev/null || true
        done
    fi

    # Remove shared memory
    rm -f "$SHM_PATH"

    echo "Cleanup complete"
}

# Set trap for cleanup
trap cleanup EXIT

# Build project
echo "=== Building Project ==="
cd "$PROJECT_DIR"
./gradlew build -x test
echo ""

# Start cluster nodes
echo "=== Starting Aeron Cluster (3 nodes) ==="
CLUSTER_PIDS=""

for node_id in 0 1 2; do
    echo "Starting cluster node $node_id..."
    java -cp "$CLASSPATH" com.microoptimus.common.cluster.ClusterNode $node_id > "cluster_node_$node_id.log" 2>&1 &
    pid=$!
    CLUSTER_PIDS="$CLUSTER_PIDS $pid"
    echo "  Node $node_id started (PID: $pid)"
done

echo "Waiting for cluster to initialize..."
sleep 5
echo ""

# Start consumer processes
echo "=== Starting Consumer Processes ==="
CONSUMER_PIDS=""

echo "Starting MM Process..."
java -cp "$CLASSPATH" com.microoptimus.signal.cluster.MMProcess > "mm_process.log" 2>&1 &
mm_pid=$!
CONSUMER_PIDS="$CONSUMER_PIDS $mm_pid"
echo "  MM Process started (PID: $mm_pid)"

echo "Starting OSM Process..."
java -cp "$CLASSPATH" com.microoptimus.osm.cluster.OSMProcess > "osm_process.log" 2>&1 &
osm_pid=$!
CONSUMER_PIDS="$CONSUMER_PIDS $osm_pid"
echo "  OSM Process started (PID: $osm_pid)"

echo "Waiting for consumers to connect..."
sleep 3
echo ""

# Run producer (sends test data)
echo "=== Running Market Data Producer ==="
echo "Sending 1000 market data events through cluster..."

java -cp "$CLASSPATH" com.microoptimus.recombinor.aeron.AeronRecombinor > "producer.log" 2>&1

echo "Producer completed"
echo ""

# Wait for processing
echo "=== Waiting for Processing ==="
echo "Allowing 5 seconds for message processing..."
sleep 5
echo ""

# Check results
echo "=== Test Results ==="

# Check if shared memory was created
if [[ -f "$SHM_PATH" ]]; then
    size=$(ls -lh "$SHM_PATH" | awk '{print $5}')
    echo "✅ Shared memory created: $SHM_PATH ($size)"
else
    echo "❌ Shared memory not found: $SHM_PATH"
fi

# Check cluster logs
echo ""
echo "=== Cluster Node Logs (Last 5 lines each) ==="
for node_id in 0 1 2; do
    echo "--- Node $node_id ---"
    if [[ -f "cluster_node_$node_id.log" ]]; then
        tail -5 "cluster_node_$node_id.log" || echo "No log content"
    else
        echo "Log file not found"
    fi
done

# Check consumer logs
echo ""
echo "=== Consumer Process Logs (Last 10 lines each) ==="

echo "--- MM Process ---"
if [[ -f "mm_process.log" ]]; then
    tail -10 "mm_process.log" || echo "No log content"
else
    echo "Log file not found"
fi

echo "--- OSM Process ---"
if [[ -f "osm_process.log" ]]; then
    tail -10 "osm_process.log" || echo "No log content"
else
    echo "Log file not found"
fi

# Check producer log
echo ""
echo "=== Producer Log (Last 10 lines) ==="
if [[ -f "producer.log" ]]; then
    tail -10 "producer.log" || echo "No log content"
else
    echo "Log file not found"
fi

echo ""
echo "=== Integration Test Complete ==="
echo "Check the logs above to verify:"
echo "1. Cluster nodes started successfully"
echo "2. Consumers received sequenced messages"
echo "3. Producer sent messages through cluster"
echo "4. Global sequencing working (check sequence numbers in logs)"
echo ""
echo "Log files saved:"
echo "- cluster_node_*.log"
echo "- mm_process.log"
echo "- osm_process.log"
echo "- producer.log"
