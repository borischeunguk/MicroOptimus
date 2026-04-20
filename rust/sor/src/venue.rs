use common::types::VenueId;

/// Configuration for a trading venue.
///
/// Ported from Java `VenueConfig.java`.
#[derive(Clone, Copy)]
pub struct VenueConfig {
    pub venue_id: VenueId,
    pub priority: u32,
    pub enabled: bool,
    pub connected: bool,
    pub max_order_size: u64,
    pub avg_latency_ns: u64,
    pub fill_rate: f64,
    pub fees_per_share: f64,
    // Dynamic state
    pub last_latency_ns: u64,
    pub recent_fill_rate: f64,
}

impl VenueConfig {
    pub fn new(
        venue_id: VenueId,
        priority: u32,
        enabled: bool,
        max_order_size: u64,
        avg_latency_ns: u64,
        fill_rate: f64,
        fees_per_share: f64,
    ) -> Self {
        Self {
            venue_id,
            priority,
            enabled,
            connected: enabled,
            max_order_size,
            avg_latency_ns,
            fill_rate,
            fees_per_share,
            last_latency_ns: avg_latency_ns,
            recent_fill_rate: fill_rate,
        }
    }

    pub fn can_handle(&self, quantity: u64) -> bool {
        self.enabled && self.connected && quantity <= self.max_order_size
    }
}

/// Scoring result for a single venue.
///
/// Ported from Java `VenueScore.java`.
#[derive(Clone, Copy, Default)]
pub struct VenueScore {
    pub venue_id: Option<VenueId>,
    pub total_score: f64,
    pub max_quantity: u64,
    pub priority_score: f64,
    pub latency_score: f64,
    pub fill_rate_score: f64,
    pub cost_score: f64,
    pub liquidity_score: f64,
    pub estimated_latency_ns: u64,
    pub estimated_fill_probability: f64,
    pub estimated_cost: f64,
}

impl VenueScore {
    pub fn init(&mut self, venue_id: VenueId) {
        self.venue_id = Some(venue_id);
        self.total_score = 0.0;
        self.max_quantity = 0;
        self.priority_score = 0.0;
        self.latency_score = 0.0;
        self.fill_rate_score = 0.0;
        self.cost_score = 0.0;
        self.liquidity_score = 0.0;
        self.estimated_latency_ns = 0;
        self.estimated_fill_probability = 0.0;
        self.estimated_cost = 0.0;
    }

    pub fn reset(&mut self) {
        *self = Self::default();
    }

    pub fn calculate_total_score(&mut self, weights: &ScoringWeights) {
        self.total_score = self.priority_score * weights.priority
            + self.latency_score * weights.latency
            + self.fill_rate_score * weights.fill_rate
            + self.cost_score * weights.cost
            + self.liquidity_score * weights.liquidity;
    }

    pub fn is_valid(&self) -> bool {
        self.venue_id.is_some() && self.total_score > 0.0
    }
}

/// Weights for venue scoring factors.
#[derive(Clone, Copy)]
pub struct ScoringWeights {
    pub priority: f64,
    pub latency: f64,
    pub fill_rate: f64,
    pub cost: f64,
    pub liquidity: f64,
}

impl Default for ScoringWeights {
    fn default() -> Self {
        Self {
            priority: 0.25,
            latency: 0.25,
            fill_rate: 0.25,
            cost: 0.15,
            liquidity: 0.10,
        }
    }
}

/// Allocation of order quantity to a venue.
///
/// Ported from Java `VenueAllocation.java`.
#[derive(Clone, Copy, Default)]
pub struct VenueAllocation {
    pub venue_id: Option<VenueId>,
    pub quantity: u64,
    pub priority: u32,
    pub estimated_latency_ns: u64,
    pub estimated_fill_probability: f64,
    pub estimated_cost: f64,
}

impl VenueAllocation {
    pub fn new(
        venue_id: VenueId,
        quantity: u64,
        priority: u32,
        latency: u64,
        fill_prob: f64,
        cost: f64,
    ) -> Self {
        Self {
            venue_id: Some(venue_id),
            quantity,
            priority,
            estimated_latency_ns: latency,
            estimated_fill_probability: fill_prob,
            estimated_cost: cost,
        }
    }
}
