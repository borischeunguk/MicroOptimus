#pragma once

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>
#include <string_view>
#include <vector>

namespace microoptimus::common {

struct BenchmarkOptions {
    std::string mode = "smoke";
    std::uint64_t samples = 0;
    std::string scenario;
};

inline BenchmarkOptions parse_benchmark_options(int argc, char** argv) {
    BenchmarkOptions opts;
    for (int i = 1; i < argc; ++i) {
        const std::string_view arg(argv[i]);
        if ((arg == "--mode") && i + 1 < argc) {
            opts.mode = argv[++i];
        } else if ((arg == "--samples") && i + 1 < argc) {
            opts.samples = static_cast<std::uint64_t>(std::stoull(argv[++i]));
        } else if ((arg == "--scenario") && i + 1 < argc) {
            opts.scenario = argv[++i];
        }
    }

    if (opts.samples == 0) {
        opts.samples = opts.mode == "full" ? 1'000'000ULL : 10'000ULL;
    }

    return opts;
}

inline bool scenario_selected(const BenchmarkOptions& opts, const std::string& scenario) {
    return opts.scenario.empty() || opts.scenario == scenario;
}

inline std::uint64_t quantile_value(std::vector<std::uint64_t> values, double q) {
    if (values.empty()) {
        return 0;
    }
    std::sort(values.begin(), values.end());
    const auto idx = static_cast<std::size_t>(q * static_cast<double>(values.size() - 1));
    return values[idx];
}

inline bool write_text_file(const std::filesystem::path& file, const std::string& content) {
    std::error_code ec;
    std::filesystem::create_directories(file.parent_path(), ec);
    std::ofstream out(file, std::ios::trunc);
    if (!out.is_open()) {
        return false;
    }
    out << content;
    return out.good();
}

} // namespace microoptimus::common

