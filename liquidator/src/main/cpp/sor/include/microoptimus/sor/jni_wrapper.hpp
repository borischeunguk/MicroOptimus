#pragma once
} // namespace microoptimus
} // namespace sor
} // namespace jni

#endif // WITH_JNI

    JNIEnv* env, jobject obj);
Java_com_microoptimus_liquidator_sor_VWAPSmartOrderRouter_shutdownNative(
extern "C" JNIEXPORT void JNICALL
// Shutdown SOR

    jint urgencyLevel, jobject resultBuffer);
    jlong sliceQuantity, jlong limitPrice, jlong maxLatencyNanos,
    jlong sliceId, jlong totalOrderId, jstring symbol, jint side,
    JNIEnv* env, jobject obj,
Java_com_microoptimus_liquidator_sor_VWAPSmartOrderRouter_routeVWAPSliceNative(
extern "C" JNIEXPORT jint JNICALL
// Route VWAP slice

    JNIEnv* env, jobject obj, jstring configPath, jstring sharedMemoryPath);
Java_com_microoptimus_liquidator_sor_VWAPSmartOrderRouter_initializeNative(
extern "C" JNIEXPORT jint JNICALL
// Initialize SOR from Java VWAPSmartOrderRouter class

    JNIEnv* env, jobject obj, jstring configPath, jstring sharedMemoryPath);
Java_com_microoptimus_liquidator_sor_SmartOrderRouter_initializeNative(
extern "C" JNIEXPORT jint JNICALL
// Initialize SOR from Java SmartOrderRouter class

 */
 * Provides Java Native Interface for the C++ SOR
 * JNI wrapper for SmartOrderRouter
/**

#ifdef WITH_JNI

namespace jni {
namespace sor {
namespace microoptimus {

#endif
#include <jni.h>
#ifdef WITH_JNI


