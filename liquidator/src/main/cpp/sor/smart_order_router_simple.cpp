// Simplified Smart Order Router using modular classes
// This version uses the production-grade modular implementation from headers

#include "microoptimus/sor/smart_order_router.hpp"

#ifdef WITH_JNI
#include <jni.h>
#endif

// All implementation is now in the modular library (libsmartorderrouter)
// This file only contains JNI wrapper code for Java integration

namespace microoptimus {
namespace sor {

#ifdef WITH_JNI
// JNI Interface
extern "C" {

static std::unique_ptr<SmartOrderRouter> g_smartOrderRouter;

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_initializeNative(
    JNIEnv* env, jobject /* obj */, jstring configPath, jstring sharedMemoryPath) {

    try {
        if (!g_smartOrderRouter) {
            g_smartOrderRouter = std::make_unique<SmartOrderRouter>();
        }

        const char* configPathStr = env->GetStringUTFChars(configPath, nullptr);
        const char* shmPathStr = env->GetStringUTFChars(sharedMemoryPath, nullptr);

        int result = g_smartOrderRouter->initialize(
            std::string(configPathStr),
            std::string(shmPathStr)
        );

        env->ReleaseStringUTFChars(configPath, configPathStr);
        env->ReleaseStringUTFChars(sharedMemoryPath, shmPathStr);

        return result;

    } catch (const std::exception& e) {
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_VWAPSmartOrderRouter_initializeNative(
    JNIEnv* env, jobject /* obj */, jstring configPath, jstring sharedMemoryPath) {

    try {
        if (!g_smartOrderRouter) {
            g_smartOrderRouter = std::make_unique<SmartOrderRouter>();
        }

        const char* configPathStr = env->GetStringUTFChars(configPath, nullptr);
        const char* shmPathStr = env->GetStringUTFChars(sharedMemoryPath, nullptr);

        int result = g_smartOrderRouter->initialize(
            std::string(configPathStr),
            std::string(shmPathStr)
        );

        env->ReleaseStringUTFChars(configPath, configPathStr);
        env->ReleaseStringUTFChars(sharedMemoryPath, shmPathStr);

        return result;

    } catch (const std::exception& e) {
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_microoptimus_liquidator_sor_VWAPSmartOrderRouter_routeVWAPSliceNative(
    JNIEnv* env, jobject /* obj */,
    jlong sliceId, jlong totalOrderId, jstring symbol, jint side,
    jlong sliceQuantity, jlong limitPrice, jlong maxLatencyNanos,
    jint urgencyLevel, jobject resultBuffer) {

    try {
        if (!g_smartOrderRouter) {
            return -1;
        }

        // Convert Java string to C++ string
        const char* symbolStr = env->GetStringUTFChars(symbol, nullptr);
        std::string symbolCpp(symbolStr);
        env->ReleaseStringUTFChars(symbol, symbolStr);

        // Create order request from VWAP slice parameters
        OrderRequest order(
            sliceId,
            symbolCpp,
            static_cast<Side>(side),
            OrderType::LIMIT,
            limitPrice,
            sliceQuantity,
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::high_resolution_clock::now().time_since_epoch()
            ).count()
        );

        // Route the order using enhanced VWAP-aware logic
        RoutingDecision decision = g_smartOrderRouter->routeOrder(order);

        // Get direct buffer pointer
        void* bufferPtr = env->GetDirectBufferAddress(resultBuffer);
        if (!bufferPtr) {
            return -2;
        }

        // Write result to buffer
        int32_t* intBuf = static_cast<int32_t*>(bufferPtr);
        int64_t* longBuf = reinterpret_cast<int64_t*>(intBuf + 2);

        // Write action and primary venue
        intBuf[0] = static_cast<int32_t>(decision.action);
        intBuf[1] = static_cast<int32_t>(decision.primaryVenue);

        // Write total quantity and estimated fill time
        longBuf[1] = decision.quantity;
        longBuf[2] = decision.estimatedFillTimeNanos;

        // Write allocations if split order
        if (decision.action == RoutingAction::SPLIT_ORDER && !decision.allocations.empty()) {
            intBuf[12] = static_cast<int32_t>(decision.allocations.size());

            // Write up to 4 allocations
            int allocIdx = 13;
            for (size_t i = 0; i < std::min(decision.allocations.size(), size_t(4)); i++) {
                const auto& alloc = decision.allocations[i];
                intBuf[allocIdx++] = static_cast<int32_t>(alloc.venue);
                *reinterpret_cast<int64_t*>(&intBuf[allocIdx]) = alloc.quantity;
                allocIdx += 2;
                intBuf[allocIdx++] = alloc.priority;
            }
        } else {
            intBuf[12] = 0; // No allocations
        }

        return 0;

    } catch (const std::exception& e) {
        return -3;
    }
}

JNIEXPORT void JNICALL
Java_com_microoptimus_liquidator_sor_VWAPSmartOrderRouter_shutdownNative(
    JNIEnv* /* env */, jobject /* obj */) {

    try {
        if (g_smartOrderRouter) {
            g_smartOrderRouter->shutdown();
            g_smartOrderRouter.reset();
        }
    } catch (...) {
        // Ignore errors during shutdown
    }
}

} // extern "C"
#endif

} // namespace sor
} // namespace microoptimus

