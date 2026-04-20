use common::types::VenueId;

use crate::order_request::OrderRequest;
use crate::venue::{ScoringWeights, VenueConfig, VenueScore};

const MAX_LATENCY_NS: u64 = 1_000_000; // 1ms
const MAX_COST_PER_SHARE: f64 = 0.001;

/// VenueScorer - Multi-factor venue scoring algorithm.
///
/// Ported from Java `VenueScorer.java`.
/// Scores venues based on priority, latency, fill rate, cost, and liquidity.
pub struct VenueScorer {
    weights: ScoringWeights,
    scores: [VenueScore; VenueId::COUNT],
}

impl VenueScorer {
    pub fn new() -> Self {
        Self {
            weights: ScoringWeights::default(),
            scores: [VenueScore::default(); VenueId::COUNT],
        }
    }

    pub fn with_weights(weights: ScoringWeights) -> Self {
        Self {
            weights,
            scores: [VenueScore::default(); VenueId::COUNT],
        }
    }

    /// Score all venues for the given order request.
    pub fn score_venues(
        &mut self,
        request: &OrderRequest,
        configs: &[Option<VenueConfig>; VenueId::COUNT],
        has_internal_liquidity: bool,
    ) -> &[VenueScore; VenueId::COUNT] {
        for (i, config_opt) in configs.iter().enumerate() {
            let config = match config_opt {
                Some(c) if c.enabled && c.connected => c,
                _ => {
                    self.scores[i].reset();
                    continue;
                }
            };

            // Skip internal if no liquidity
            if config.venue_id == VenueId::Internal && !has_internal_liquidity {
                self.scores[i].reset();
                continue;
            }

            // Note: we don't filter by quantity here because the order
            // may be split across venues. The splitter handles max quantity.

            self.score_venue(i, request, config);
        }

        &self.scores
    }

    fn score_venue(&mut self, idx: usize, request: &OrderRequest, config: &VenueConfig) {
        let score = &mut self.scores[idx];
        score.init(config.venue_id);
        score.max_quantity = config.max_order_size;

        // Priority score (0-100 -> 0-1)
        score.priority_score = config.priority as f64 / 100.0;

        // Latency score (lower is better)
        let latency = config.last_latency_ns;
        score.latency_score = 1.0 - (latency as f64 / MAX_LATENCY_NS as f64).min(1.0);
        score.estimated_latency_ns = latency;

        // Fill rate score
        score.fill_rate_score = config.recent_fill_rate;
        score.estimated_fill_probability = config.recent_fill_rate;

        // Cost score (lower is better)
        let cost_per_share = config.fees_per_share;
        score.cost_score = 1.0 - (cost_per_share / MAX_COST_PER_SHARE).min(1.0);
        score.estimated_cost = cost_per_share * request.quantity as f64;

        // Liquidity score
        score.liquidity_score = if config.venue_id == VenueId::Internal {
            1.0
        } else {
            0.8
        };

        score.calculate_total_score(&self.weights);
    }

    pub fn weights(&self) -> &ScoringWeights {
        &self.weights
    }

    pub fn set_weights(&mut self, weights: ScoringWeights) {
        self.weights = weights;
    }
}

impl Default for VenueScorer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::types::{OrderFlowType, OrderType, Side, TimeInForce};

    fn make_request(quantity: u64) -> OrderRequest {
        OrderRequest {
            sequence_id: 1,
            order_id: 1,
            client_id: 1,
            parent_order_id: 0,
            symbol_index: 0,
            side: Side::Buy,
            order_type: OrderType::Limit,
            price: 15_000_000,
            quantity,
            time_in_force: TimeInForce::Ioc,
            flow_type: OrderFlowType::Dma,
            timestamp: 0,
        }
    }

    fn make_configs() -> [Option<VenueConfig>; VenueId::COUNT] {
        [
            None, // Internal - skip for external-only test
            Some(VenueConfig::new(
                VenueId::Cme, 90, true, 1_000_000, 150_000, 0.95, 0.0001,
            )),
            Some(VenueConfig::new(
                VenueId::Nasdaq, 85, true, 500_000, 200_000, 0.93, 0.0002,
            )),
        ]
    }

    #[test]
    fn test_score_venues() {
        let mut scorer = VenueScorer::new();
        let request = make_request(1000);
        let configs = make_configs();

        let scores = scorer.score_venues(&request, &configs, false);

        // Internal should be zero (no config)
        assert!(!scores[VenueId::Internal.index()].is_valid());

        // CME should score higher than NASDAQ (higher priority, lower latency, higher fill rate)
        let cme = &scores[VenueId::Cme.index()];
        let nasdaq = &scores[VenueId::Nasdaq.index()];
        assert!(cme.is_valid());
        assert!(nasdaq.is_valid());
        assert!(
            cme.total_score > nasdaq.total_score,
            "CME ({}) should score higher than NASDAQ ({})",
            cme.total_score,
            nasdaq.total_score
        );
    }

    #[test]
    fn test_large_order_still_scored_for_splitting() {
        let mut scorer = VenueScorer::new();
        let request = make_request(2_000_000); // larger than any venue max
        let configs = make_configs();

        let scores = scorer.score_venues(&request, &configs, false);
        // Venues are still scored even if order exceeds max (splitting handles quantity limits)
        assert!(scores[VenueId::Cme.index()].is_valid());
        assert!(scores[VenueId::Nasdaq.index()].is_valid());
    }

    #[test]
    fn test_internal_with_liquidity() {
        let mut scorer = VenueScorer::new();
        let request = make_request(500);
        let mut configs = make_configs();
        configs[VenueId::Internal.index()] = Some(VenueConfig::new(
            VenueId::Internal, 100, true, 10_000_000, 0, 1.0, 0.0,
        ));

        let scores = scorer.score_venues(&request, &configs, true);
        let internal = &scores[VenueId::Internal.index()];
        assert!(internal.is_valid());
        // Internal should score highest with liquidity
        assert!(internal.total_score > scores[VenueId::Cme.index()].total_score);
    }

    #[test]
    fn test_disabled_venue_not_scored() {
        let mut scorer = VenueScorer::new();
        let request = make_request(100);
        let configs = [
            None,
            Some(VenueConfig::new(VenueId::Cme, 90, false, 1_000_000, 150_000, 0.95, 0.0001)),
            None,
        ];

        let scores = scorer.score_venues(&request, &configs, false);
        assert!(!scores[VenueId::Cme.index()].is_valid());
    }
}
