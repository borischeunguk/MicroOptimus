#include <chrono>
#include <cstdint>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "microoptimus/algo/algo_order.hpp"
#include "microoptimus/algo/vwap_algorithm.hpp"
#include "microoptimus/common/benchmark_utils.hpp"
#include "microoptimus/common/types.hpp"

namespace {

using namespace microoptimus;

struct Scenario {
    const char* name;
    common::Quantity parent_qty;
    common::Nanos start_ns;
    common::Nanos end_ns;
    common::Nanos tick_step_ns;
    common::Price base_price;
    common::Quantity max_slice_size;
    double participation_rate;
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
    {"algo_s1_steady", 40'000, 0, 8'000'000, 50'000, 15'000'000, 4'000, 0.12},
    {"algo_s2_open_burst", 60'000, 0, 6'000'000, 20'000, 15'010'000, 8'000, 0.18},
    {"algo_s3_thin_liquidity", 30'000, 0, 10'000'000, 80'000, 14'990'000, 2'000, 0.08},
    {"algo_s4_large_parent", 250'000, 0, 20'000'000, 40'000, 15'020'000, 12'000, 0.15},
};

std::pair<std::uint64_t, std::uint64_t> run_parent_order(const Scenario& s, std::uint64_t seed) {
    auto started = std::chrono::steady_clock::now();

    algo::AlgoOrder order;
    order.init(seed, 7'000 + seed, 0, common::Side::Buy, s.parent_qty, s.base_price, s.start_ns, s.end_ns);
    order.params.num_buckets = 12;
    order.params.participation_rate = s.participation_rate;
    order.params.min_slice_size = 100;
    order.params.max_slice_size = s.max_slice_size;
    order.params.slice_interval_ns = 0;

    algo::VwapAlgorithm vwap;
    vwap.initialize(order);

    Lcg rng(seed);
    common::Nanos now = s.start_ns;
    std::uint64_t children = 0;

    while (now <= s.end_ns && order.leaves_quantity > 0) {
        const auto jitter = static_cast<std::int64_t>(rng.bounded(1000)) - 500;
        const auto price = static_cast<common::Price>(std::max<std::int64_t>(1, static_cast<std::int64_t>(s.base_price) + jitter));
        std::size_t slice_idx = 0;
        if (vwap.generate_slice(order, now, price, slice_idx)) {
            const auto& slice = vwap.get_slice(slice_idx);
            if (slice.quantity > 0) {
                order.on_slice_fill(slice.quantity);
                vwap.on_slice_execution(slice.quantity);
                ++children;
            }
        }
        now += s.tick_step_ns;
    }

    if (order.leaves_quantity > 0) {
        order.on_slice_fill(order.leaves_quantity);
    }

    auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started);
    return {static_cast<std::uint64_t>(elapsed.count()), children};
}

std::string build_report(const Scenario& s,
                         std::uint64_t samples,
                         std::uint64_t total_children,
                         std::uint64_t p90,
                         std::uint64_t p99,
                         std::uint64_t p999,
                         double throughput_parent,
                         double throughput_child) {
    std::ostringstream out;
    out << "{\n";
    out << "  \"bench\": \"algo_vwap_latency\",\n";
    out << "  \"scenario\": \"" << s.name << "\",\n";
    out << "  \"samples\": " << samples << ",\n";
    out << "  \"latency_ns_p90\": " << p90 << ",\n";
    out << "  \"latency_ns_p99\": " << p99 << ",\n";
    out << "  \"latency_ns_p999\": " << p999 << ",\n";
    out << "  \"throughput_parent_per_sec\": " << throughput_parent << ",\n";
    out << "  \"throughput_child_per_sec\": " << throughput_child << "\n";
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

        std::vector<std::uint64_t> latencies;
        latencies.reserve(static_cast<std::size_t>(opts.samples));

        auto wall_start = std::chrono::steady_clock::now();
        std::uint64_t total_children = 0;

        for (std::uint64_t i = 0; i < opts.samples; ++i) {
            const auto [latency_ns, children] = run_parent_order(scenario, i + 1);
            latencies.push_back(latency_ns);
            total_children += children;
        }

        const auto wall_ns = static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - wall_start).count());
        const double throughput_parent = static_cast<double>(opts.samples) * 1e9 / wall_ns;
        const double throughput_child = static_cast<double>(total_children) * 1e9 / wall_ns;

        const auto p90 = common::quantile_value(latencies, 0.90);
        const auto p99 = common::quantile_value(latencies, 0.99);
        const auto p999 = common::quantile_value(latencies, 0.999);

        const auto report = build_report(
            scenario,
            opts.samples,
            total_children,
            p90,
            p99,
            p999,
            throughput_parent,
            throughput_child);

        const auto report_file = std::string(ALGO_MODULE_ROOT) + "/perf-reports/cpp_vwap_latency_" + scenario.name + ".json";
        if (!common::write_text_file(report_file, report)) {
            std::cerr << "failed to write report: " << report_file << '\n';
            return 1;
        }

        std::cout << "[vwap_latency] scenario=" << scenario.name
                  << " samples=" << opts.samples
                  << " p99_ns=" << p99
                  << " throughput_parent_per_sec=" << throughput_parent << '\n';
    }

    return 0;
}

