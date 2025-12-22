#!/bin/bash
# Final validation script for C++ Smart Order Router implementation
# Verifies all components are working correctly

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"

echo "══════════════════════════════════════════════════════════════"
echo "  C++ SMART ORDER ROUTER - FINAL VALIDATION"
echo "══════════════════════════════════════════════════════════════"
echo ""

# Check if build exists
if [ ! -d "${BUILD_DIR}" ]; then
    echo "❌ Build directory not found. Running build first..."
    "${SCRIPT_DIR}/build_and_test_sor.sh"
fi

cd "${BUILD_DIR}/liquidator/src/main/cpp"

# Validation tests
TOTAL_CHECKS=0
PASSED_CHECKS=0

echo "Running validation checks..."
echo ""

# Check 1: Unit test executable exists
((TOTAL_CHECKS++))
if [ -f "./sor_unit_tests" ]; then
    echo "✅ Check 1/6: Unit test executable found"
    ((PASSED_CHECKS++))
else
    echo "❌ Check 1/6: Unit test executable NOT found"
fi

# Check 2: Performance test executable exists
((TOTAL_CHECKS++))
if [ -f "./sor_perf_test" ]; then
    echo "✅ Check 2/6: Performance test executable found"
    ((PASSED_CHECKS++))
else
    echo "❌ Check 2/6: Performance test executable NOT found"
fi

# Check 3: Shared library exists
((TOTAL_CHECKS++))
if [ -f "./libsmartorderrouter.dylib" ] || [ -f "./libsmartorderrouter.so" ]; then
    echo "✅ Check 3/6: SOR shared library found"
    ((PASSED_CHECKS++))
else
    echo "❌ Check 3/6: SOR shared library NOT found"
fi

# Check 4: Run unit tests
((TOTAL_CHECKS++))
echo ""
echo "Running unit tests..."
if ./sor_unit_tests > /tmp/sor_unit_test_output.txt 2>&1; then
    TEST_COUNT=$(grep -o "\[  PASSED  \] [0-9]* test" /tmp/sor_unit_test_output.txt | grep -o "[0-9]*" || echo "0")
    echo "✅ Check 4/6: Unit tests passed (${TEST_COUNT} tests)"
    ((PASSED_CHECKS++))
else
    echo "❌ Check 4/6: Unit tests FAILED"
    cat /tmp/sor_unit_test_output.txt
fi

# Check 5: Run performance test
((TOTAL_CHECKS++))
echo ""
echo "Running performance test..."
if ./sor_perf_test > /tmp/sor_perf_test_output.txt 2>&1; then
    THROUGHPUT=$(grep "Throughput:" /tmp/sor_perf_test_output.txt | grep -o "[0-9]*" | head -1 || echo "0")
    AVG_LATENCY=$(grep "Avg:" /tmp/sor_perf_test_output.txt | grep -o "[0-9]*" | head -1 || echo "999999")

    if [ "$THROUGHPUT" -gt 1000000 ] && [ "$AVG_LATENCY" -lt 500 ]; then
        echo "✅ Check 5/6: Performance test passed"
        echo "   Throughput: ${THROUGHPUT} orders/sec (>1M target)"
        echo "   Avg Latency: ${AVG_LATENCY} ns (<500ns target)"
        ((PASSED_CHECKS++))
    else
        echo "❌ Check 5/6: Performance targets NOT met"
        echo "   Throughput: ${THROUGHPUT} orders/sec"
        echo "   Avg Latency: ${AVG_LATENCY} ns"
    fi
else
    echo "❌ Check 5/6: Performance test FAILED to run"
    cat /tmp/sor_perf_test_output.txt
fi

# Check 6: Verify documentation exists
((TOTAL_CHECKS++))
cd "${SCRIPT_DIR}"
DOC_COUNT=0
[ -f "liquidator/README_SOR.md" ] && ((DOC_COUNT++))
[ -f "liquidator/README_SOR_INTEGRATION.md" ] && ((DOC_COUNT++))
[ -f "MODULAR_REFACTORING_COMPLETE.md" ] && ((DOC_COUNT++))
[ -f "SOR_IMPLEMENTATION_COMPLETE.md" ] && ((DOC_COUNT++))
[ -f "CMAKE_BUILD_GUIDE.md" ] && ((DOC_COUNT++))

if [ "$DOC_COUNT" -ge 4 ]; then
    echo "✅ Check 6/6: Documentation complete (${DOC_COUNT}/5 files)"
    ((PASSED_CHECKS++))
else
    echo "❌ Check 6/6: Documentation incomplete (${DOC_COUNT}/5 files)"
fi

# Final summary
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  VALIDATION SUMMARY"
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "Checks passed: ${PASSED_CHECKS}/${TOTAL_CHECKS}"
echo ""

if [ "$PASSED_CHECKS" -eq "$TOTAL_CHECKS" ]; then
    echo "🎉 ALL VALIDATION CHECKS PASSED!"
    echo ""
    echo "The C++ Smart Order Router implementation is:"
    echo "  ✅ Fully compiled and linked"
    echo "  ✅ All unit tests passing"
    echo "  ✅ Performance targets met"
    echo "  ✅ Documentation complete"
    echo ""
    echo "Status: PRODUCTION-READY ✅"
    echo ""
    exit 0
else
    echo "⚠️  Some validation checks failed."
    echo "Please review the output above for details."
    echo ""
    exit 1
fi

