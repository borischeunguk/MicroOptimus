#ifndef TRADE_HPP
#define TRADE_HPP

#include <string>
#include <chrono>
#include <cstdint>

namespace MicroOptimus {

class Trade {
public:
    uint64_t tradeId;
    uint64_t buyOrderId;
    uint64_t sellOrderId;
    std::string symbol;
    double price;
    uint64_t quantity;
    std::chrono::system_clock::time_point timestamp;
    std::string buyClientId;
    std::string sellClientId;

    Trade(uint64_t id, uint64_t buyId, uint64_t sellId,
          const std::string& sym, double p, uint64_t qty,
          const std::string& buyClient, const std::string& sellClient)
        : tradeId(id), buyOrderId(buyId), sellOrderId(sellId),
          symbol(sym), price(p), quantity(qty),
          buyClientId(buyClient), sellClientId(sellClient) {
        timestamp = std::chrono::system_clock::now();
    }
};

} // namespace MicroOptimus

#endif // TRADE_HPP
