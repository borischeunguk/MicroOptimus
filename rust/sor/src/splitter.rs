use common::types::VenueId;

use crate::order_request::OrderRequest;
use crate::venue::{VenueAllocation, VenueScore};

const MAX_SPLIT_VENUES: usize = 3;

/// OrderSplitter - Splits large orders across multiple venues.
///
/// Ported from Java `OrderSplitter.java`. Uses pro-rata splitting by score.
pub struct OrderSplitter {
    max_venues: usize,
}

impl OrderSplitter {
    pub fn new() -> Self {
        Self {
            max_venues: MAX_SPLIT_VENUES,
        }
    }

    /// Split an order across venues proportional to their scores.
    ///
    /// Returns a Vec of allocations sorted by priority (highest score first).
    pub fn split_order(
        &self,
        request: &OrderRequest,
        scores: &[VenueScore; VenueId::COUNT],
    ) -> Vec<VenueAllocation> {
        // Collect and sort valid scores by total_score descending
        let mut sorted: Vec<(usize, f64)> = scores
            .iter()
            .enumerate()
            .filter(|(_, s)| s.is_valid())
            .map(|(i, s)| (i, s.total_score))
            .collect();
        sorted.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

        let venues_to_use = sorted.len().min(self.max_venues);
        if venues_to_use == 0 {
            return Vec::new();
        }

        // Calculate total score for pro-rata
        let total_score: f64 = sorted[..venues_to_use].iter().map(|(_, s)| s).sum();
        if total_score <= 0.0 {
            return Vec::new();
        }

        let mut allocations = Vec::with_capacity(venues_to_use);
        let mut remaining = request.quantity;

        for (priority_idx, &(score_idx, _)) in sorted[..venues_to_use].iter().enumerate() {
            let score = &scores[score_idx];
            let proportion = score.total_score / total_score;

            let qty = if priority_idx == venues_to_use - 1 {
                // Last venue gets remainder
                remaining
            } else {
                let q = (request.quantity as f64 * proportion) as u64;
                q.min(remaining).min(score.max_quantity)
            };

            if qty > 0 {
                allocations.push(VenueAllocation::new(
                    score.venue_id.unwrap(),
                    qty,
                    (priority_idx + 1) as u32,
                    score.estimated_latency_ns,
                    score.estimated_fill_probability,
                    score.estimated_cost * qty as f64 / request.quantity as f64,
                ));
                remaining = remaining.saturating_sub(qty);
            }
        }

        allocations
    }

    pub fn set_max_venues(&mut self, max: usize) {
        self.max_venues = max;
    }
}

impl Default for OrderSplitter {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::types::*;

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

    fn make_scores() -> [VenueScore; VenueId::COUNT] {
        let mut scores = [VenueScore::default(); VenueId::COUNT];

        // CME: higher score
        scores[VenueId::Cme.index()].init(VenueId::Cme);
        scores[VenueId::Cme.index()].total_score = 0.85;
        scores[VenueId::Cme.index()].max_quantity = 1_000_000;
        scores[VenueId::Cme.index()].estimated_latency_ns = 150_000;
        scores[VenueId::Cme.index()].estimated_fill_probability = 0.95;
        scores[VenueId::Cme.index()].estimated_cost = 10.0;

        // NASDAQ: lower score
        scores[VenueId::Nasdaq.index()].init(VenueId::Nasdaq);
        scores[VenueId::Nasdaq.index()].total_score = 0.75;
        scores[VenueId::Nasdaq.index()].max_quantity = 500_000;
        scores[VenueId::Nasdaq.index()].estimated_latency_ns = 200_000;
        scores[VenueId::Nasdaq.index()].estimated_fill_probability = 0.93;
        scores[VenueId::Nasdaq.index()].estimated_cost = 20.0;

        scores
    }

    #[test]
    fn test_pro_rata_split() {
        let splitter = OrderSplitter::new();
        let request = make_request(10_000);
        let scores = make_scores();

        let allocations = splitter.split_order(&request, &scores);
        assert_eq!(allocations.len(), 2);

        // Total quantity should equal request
        let total: u64 = allocations.iter().map(|a| a.quantity).sum();
        assert_eq!(total, 10_000);

        // First allocation should be CME (higher score)
        assert_eq!(allocations[0].venue_id, Some(VenueId::Cme));
        assert_eq!(allocations[0].priority, 1);

        // CME should get more quantity (higher score proportion)
        assert!(allocations[0].quantity > allocations[1].quantity);
    }

    #[test]
    fn test_no_valid_venues() {
        let splitter = OrderSplitter::new();
        let request = make_request(1000);
        let scores = [VenueScore::default(); VenueId::COUNT];

        let allocations = splitter.split_order(&request, &scores);
        assert!(allocations.is_empty());
    }

    #[test]
    fn test_single_venue() {
        let splitter = OrderSplitter::new();
        let request = make_request(1000);
        let mut scores = [VenueScore::default(); VenueId::COUNT];

        scores[VenueId::Cme.index()].init(VenueId::Cme);
        scores[VenueId::Cme.index()].total_score = 0.85;
        scores[VenueId::Cme.index()].max_quantity = 1_000_000;

        let allocations = splitter.split_order(&request, &scores);
        assert_eq!(allocations.len(), 1);
        assert_eq!(allocations[0].quantity, 1000);
    }
}
