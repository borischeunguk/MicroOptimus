#include <chrono>
#include <cstdint>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "microoptimus/common/benchmark_utils.hpp"
#include "microoptimus/common/types.hpp"
#include "microoptimus/mvp/smart_order_router.hpp"

namespace {

using namespace microoptimus;

struct Scenario {
    const char* name;
    common::Quantity min_qty;
    common::Quantity qty_span;
    common::Price price;
    common::Nanos cme_latency_ns;
    common::Nanos nasq_latency_ns;
    double cme_fill_rate;
    double nasq_fill_rate;
};

struct Lcg {
    std::uint64_t state;
    explicit Lcg(std::uint64_t seed) : state(seed) {}

    std::uint64_t next_u64() {
        state = state * 6364136223846793005ULL + 1442695040888963407ULL;
        return state;
    }

    std::uint64_t bounded(std::uint64_t bound) {
        return bound == 0 ? 0 : (next_u64() % bound);
    }
};

constexpr Scenario kScenarios[] = {
    {"sor_s1_steady", 200, 2'000, 1'500, 130'000, 170'000, 0.95, 0.92},
    {"sor_s2_open_burst", 300, 4'000, 1'505, 140'000, 180'000, 0.94, 0.90},
    {"sor_s3_thin_liquidity", 200, 5'000, 1'495, 180'000, 210'000, 0.86, 0.83},
    {"sor_s4_large_parent_children", 500, 8'000, 1'510, 150'000, 190'000, 0.93, 0.90},
};

mvp::OrderRequest make_request(std::uint64_t seq, const Scenario& s, Lcg& rng) {
    const auto quantity = s.min_qty + rng.bounded(s.qty_span);
    mvp::OrderRequest req;
    req.sequence_id = seq;
    req.order_id = 20'000 + seq;
    req.client_id = 5;
    req.parent_order_id = 1'000 + seq;
    req.symbol_index = 0;
    req.side = rng.bounded(2) == 0 ? common::Side::Buy : common::Side::Sell;
    req.order_type = common::OrderType::Limit;
    req.price = s.price;
    req.quantity = quantity;
    req.time_in_force = common::TimeInForce::Ioc;
    req.flow_type = common::OrderFlowType::AlgoSlice;
    req.timestamp = seq;
    return req;
}

std::string build_report(const Scenario& s,
                         std::uint64_t samples,
                         std::uint64_t p90,
                         std::uint64_t p99,
                         std::uint64_t p999,
                         double throughput) {
    std::ostringstream out;
    out << "{\n";
    out << "  \"bench\": \"sor_router_latency\",\n";
    out << "  \"scenario\": \"" << s.name << "\",\n";
    out << "  \"samples\": " << samples << ",\n";
    out << "  \"latency_ns_p90\": " << p90 << ",\n";
    out << "  \"latency_ns_p99\": " << p99 << ",\n";
    out << "  \"latency_ns_p999\": " << p999 << ",\n";
    out << "  \"throughput_routes_per_sec\": " << throughput << "\n";
    out << "}\n";
    return out.str();
}

} // namespace

int main(int argc, char** argv) {
    const auto opts = common::parse_benchmark_options(argc, argv);

    for (const auto& scenario : kScenarios) {
        if (!common::scenario_selected(opts, scenario.name)) {
            continue;
        }

        mvp::SmartOrderRouter router;
        router.initialize();
        router.set_internal_liquidity_threshold(100);
        router.configure_venue(mvp::VenueConfig{common::VenueId::Cme, 90, true, 1'500'000, scenario.cme_latency_ns, scenario.cme_fill_rate, 0.00010});
        router.configure_venue(mvp::VenueConfig{common::VenueId::Nasq, 85, true, 1'200'000, scenario.nasq_latency_ns, scenario.nasq_fill_rate, 0.00015});

        Lcg rng(99);
        std::vector<std::uint64_t> latencies;
        latencies.reserve(static_cast<std::size_t>(opts.samples));

        const auto wall_start = std::chrono::steady_clock::now();
        for (std::uint64_t i = 0; i < opts.samples; ++i) {
            const auto req = make_request(i + 1, scenario, rng);
            const auto start = std::chrono::steady_clock::now();
            const auto decision = router.route_order(req);
            const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - start).count();
            latencies.push_back(static_cast<std::uint64_t>(elapsed));
            (void)decision;
        }
        const auto wall_ns = static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - wall_start).count());

        const auto p90 = common::quantile_value(latencies, 0.90);
        const auto p99 = common::quantile_value(latencies, 0.99);
        const auto p999 = common::quantile_value(latencies, 0.999);
        const double throughput = static_cast<double>(opts.samples) * 1e9 / wall_ns;

        const auto report = build_report(scenario, opts.samples, p90, p99, p999, throughput);
        const auto report_file = std::string(LIQUIDATOR_MODULE_ROOT) + "/perf-reports/cpp_router_latency_" + scenario.name + ".json";
        if (!common::write_text_file(report_file, report)) {
            std::cerr << "failed to write report: " << report_file << '\n';
            return 1;
        }

        std::cout << "[router_latency] scenario=" << scenario.name
                  << " samples=" << opts.samples
                  << " p99_ns=" << p99
                  << " throughput_routes_per_sec=" << throughput << '\n';
    }

    return 0;
}

