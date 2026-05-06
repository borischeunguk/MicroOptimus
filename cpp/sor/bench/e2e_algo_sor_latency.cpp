#include <algorithm>
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
#include "microoptimus/mvp/smart_order_router.hpp"

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
    {"e2e_s1_steady", 40'000, 0, 8'000'000, 40'000, 1'500, 4'500, 0.12, 130'000, 170'000, 0.95, 0.92},
    {"e2e_s2_open_burst", 55'000, 0, 6'000'000, 20'000, 1'505, 8'000, 0.18, 140'000, 180'000, 0.94, 0.91},
    {"e2e_s3_thin_liquidity", 25'000, 0, 10'000'000, 75'000, 1'495, 2'500, 0.08, 180'000, 220'000, 0.86, 0.83},
    {"e2e_s4_large_parent", 240'000, 0, 20'000'000, 30'000, 1'510, 12'000, 0.15, 150'000, 190'000, 0.93, 0.90},
};

struct ParentResult {
    std::uint64_t parent_latency_ns = 0;
    std::uint64_t children = 0;
    std::vector<std::uint64_t> child_latencies;
};

ParentResult run_parent(const Scenario& s, std::uint64_t seed, mvp::SmartOrderRouter& router) {
    const auto parent_start = std::chrono::steady_clock::now();

    algo::AlgoOrder order;
    order.init(seed, 8'000 + seed, 0, common::Side::Buy, s.parent_qty, s.base_price, s.start_ns, s.end_ns);
    order.params.num_buckets = 12;
    order.params.participation_rate = s.participation_rate;
    order.params.min_slice_size = 100;
    order.params.max_slice_size = s.max_slice_size;
    order.params.slice_interval_ns = 0;

    algo::VwapAlgorithm vwap;
    vwap.initialize(order);

    std::vector<std::uint64_t> child_latencies;
    child_latencies.reserve(64);

    Lcg rng(seed);
    common::Nanos now = s.start_ns;
    std::uint64_t children = 0;

    while (now <= s.end_ns && order.leaves_quantity > 0) {
        const auto jitter = static_cast<std::int64_t>(rng.bounded(1000)) - 500;
        const auto current_price = static_cast<common::Price>(
            std::max<std::int64_t>(1, static_cast<std::int64_t>(s.base_price) + jitter));

        std::size_t idx = 0;
        if (vwap.generate_slice(order, now, current_price, idx)) {
            const auto& slice = vwap.get_slice(idx);
            if (slice.quantity > 0) {
                mvp::OrderRequest req;
                req.sequence_id = seed;
                req.order_id = slice.slice_id;
                req.client_id = order.client_id;
                req.parent_order_id = order.algo_order_id;
                req.symbol_index = order.symbol_index;
                req.side = slice.side;
                req.order_type = common::OrderType::Limit;
                req.price = slice.price;
                req.quantity = slice.quantity;
                req.time_in_force = common::TimeInForce::Ioc;
                req.flow_type = common::OrderFlowType::AlgoSlice;
                req.timestamp = now;

                const auto route_start = std::chrono::steady_clock::now();
                const auto decision = router.route_order(req);
                (void)decision;
                const auto route_elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now() - route_start).count();
                child_latencies.push_back(static_cast<std::uint64_t>(route_elapsed));

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

    const auto parent_elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - parent_start).count();

    return ParentResult{static_cast<std::uint64_t>(parent_elapsed), children, std::move(child_latencies)};
}

std::string build_report(const Scenario& s,
                         std::uint64_t samples,
                         std::uint64_t parent_p90,
                         std::uint64_t parent_p99,
                         std::uint64_t parent_p999,
                         std::uint64_t child_p90,
                         std::uint64_t child_p99,
                         std::uint64_t child_p999,
                         double throughput_parent,
                         double throughput_child,
                         std::uint64_t total_children) {
    std::ostringstream out;
    out << "{\n";
    out << "  \"bench\": \"e2e_algo_sor_latency\",\n";
    out << "  \"scenario\": \"" << s.name << "\",\n";
    out << "  \"samples\": " << samples << ",\n";
    out << "  \"parent_latency_ns_p90\": " << parent_p90 << ",\n";
    out << "  \"parent_latency_ns_p99\": " << parent_p99 << ",\n";
    out << "  \"parent_latency_ns_p999\": " << parent_p999 << ",\n";
    out << "  \"child_latency_ns_p90\": " << child_p90 << ",\n";
    out << "  \"child_latency_ns_p99\": " << child_p99 << ",\n";
    out << "  \"child_latency_ns_p999\": " << child_p999 << ",\n";
    out << "  \"throughput_parent_per_sec\": " << throughput_parent << ",\n";
    out << "  \"throughput_child_per_sec\": " << throughput_child << ",\n";
    out << "  \"total_children\": " << total_children << "\n";
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

        std::vector<std::uint64_t> parent_latencies;
        parent_latencies.reserve(static_cast<std::size_t>(opts.samples));
        std::vector<std::uint64_t> child_latencies;
        child_latencies.reserve(static_cast<std::size_t>(opts.samples) * 24);

        const auto wall_start = std::chrono::steady_clock::now();
        std::uint64_t total_children = 0;
        for (std::uint64_t i = 0; i < opts.samples; ++i) {
            auto result = run_parent(scenario, i + 1, router);
            parent_latencies.push_back(result.parent_latency_ns);
            total_children += result.children;
            child_latencies.insert(child_latencies.end(), result.child_latencies.begin(), result.child_latencies.end());
        }
        const auto wall_ns = static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - wall_start).count());

        const auto parent_p90 = common::quantile_value(parent_latencies, 0.90);
        const auto parent_p99 = common::quantile_value(parent_latencies, 0.99);
        const auto parent_p999 = common::quantile_value(parent_latencies, 0.999);
        const auto child_p90 = common::quantile_value(child_latencies, 0.90);
        const auto child_p99 = common::quantile_value(child_latencies, 0.99);
        const auto child_p999 = common::quantile_value(child_latencies, 0.999);

        const double throughput_parent = static_cast<double>(opts.samples) * 1e9 / wall_ns;
        const double throughput_child = static_cast<double>(total_children) * 1e9 / wall_ns;

        const auto report = build_report(
            scenario,
            opts.samples,
            parent_p90,
            parent_p99,
            parent_p999,
            child_p90,
            child_p99,
            child_p999,
            throughput_parent,
            throughput_child,
            total_children);

        const auto report_file = std::string(LIQUIDATOR_MODULE_ROOT) + "/perf-reports/cpp_e2e_algo_sor_latency_" + scenario.name + ".json";
        if (!common::write_text_file(report_file, report)) {
            std::cerr << "failed to write report: " << report_file << '\n';
            return 1;
        }

        std::cout << "[e2e_algo_sor_latency] scenario=" << scenario.name
                  << " samples=" << opts.samples
                  << " parent_p99_ns=" << parent_p99
                  << " child_p99_ns=" << child_p99 << '\n';
    }

    return 0;
}

