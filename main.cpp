#include <iostream>
#include <string>
#include <chrono>

// Example playground for testing MicroOptimus components
int main() {
    std::cout << "=== MicroOptimus Playground ===" << std::endl;
    std::cout << "Project: Ultra-low latency trading system" << std::endl;
    std::cout << "Build time: " << __DATE__ << " " << __TIME__ << std::endl;
    std::cout << std::endl;

    // Example: Simple timestamp test
    auto now = std::chrono::high_resolution_clock::now();
    auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        now.time_since_epoch()
    ).count();

    std::cout << "Current timestamp: " << nanos << " nanoseconds" << std::endl;
    std::cout << std::endl;

    // TODO: Add your experimental code here
    std::cout << "Ready for testing!" << std::endl;

    return 0;
}

