/// Side of an order (Buy or Sell).
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Buy = 0,
    Sell = 1,
}

impl Side {
    pub fn opposite(self) -> Self {
        match self {
            Side::Buy => Side::Sell,
            Side::Sell => Side::Buy,
        }
    }
}

/// Order type classification.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderType {
    Market = 0,
    Limit = 1,
    Stop = 2,
    StopLimit = 3,
}

/// Time-in-force for an order.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimeInForce {
    Ioc = 0,
    Gtc = 1,
    Day = 2,
}

/// Algorithm type for algo orders.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlgorithmType {
    Vwap = 1,
    Twap = 2,
    Iceberg = 3,
    Pov = 4,
    Is = 5,
}

/// Venue identifier.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum VenueId {
    Internal = 1,
    Cme = 2,
    Nasdaq = 3,
}

impl VenueId {
    pub const COUNT: usize = 3;

    pub fn index(self) -> usize {
        match self {
            VenueId::Internal => 0,
            VenueId::Cme => 1,
            VenueId::Nasdaq => 2,
        }
    }

    pub const ALL: [VenueId; 3] = [VenueId::Internal, VenueId::Cme, VenueId::Nasdaq];
}

/// Routing action decided by the SOR.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RoutingAction {
    RouteExternal = 0,
    RouteInternal = 1,
    SplitOrder = 2,
    Reject = 3,
}

/// Classification of order flow origin.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderFlowType {
    Dma = 0,
    Principal = 1,
    AlgoSlice = 2,
}

/// Algo order state machine.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlgoOrderState {
    Pending = 0,
    Working = 1,
    Paused = 2,
    PartialFill = 3,
    Filled = 4,
    Cancelled = 5,
    Rejected = 6,
    Expired = 7,
}

impl AlgoOrderState {
    pub fn can_generate_slices(self) -> bool {
        matches!(self, AlgoOrderState::Working | AlgoOrderState::PartialFill)
    }

    pub fn can_start(self) -> bool {
        self == AlgoOrderState::Pending
    }

    pub fn can_pause(self) -> bool {
        matches!(self, AlgoOrderState::Working | AlgoOrderState::PartialFill)
    }

    pub fn can_resume(self) -> bool {
        self == AlgoOrderState::Paused
    }

    pub fn can_cancel(self) -> bool {
        matches!(
            self,
            AlgoOrderState::Pending
                | AlgoOrderState::Working
                | AlgoOrderState::Paused
                | AlgoOrderState::PartialFill
        )
    }

    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            AlgoOrderState::Filled
                | AlgoOrderState::Cancelled
                | AlgoOrderState::Rejected
                | AlgoOrderState::Expired
        )
    }

    pub fn is_active(self) -> bool {
        matches!(
            self,
            AlgoOrderState::Working | AlgoOrderState::PartialFill | AlgoOrderState::Paused
        )
    }
}

/// Slice state machine.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SliceState {
    Pending = 0,
    Sent = 1,
    Acked = 2,
    PartialFill = 3,
    Filled = 4,
    Cancelled = 5,
    Rejected = 6,
}

impl SliceState {
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            SliceState::Filled | SliceState::Cancelled | SliceState::Rejected
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_side_opposite() {
        assert_eq!(Side::Buy.opposite(), Side::Sell);
        assert_eq!(Side::Sell.opposite(), Side::Buy);
    }

    #[test]
    fn test_algo_order_state_transitions() {
        assert!(AlgoOrderState::Pending.can_start());
        assert!(!AlgoOrderState::Working.can_start());

        assert!(AlgoOrderState::Working.can_generate_slices());
        assert!(AlgoOrderState::PartialFill.can_generate_slices());
        assert!(!AlgoOrderState::Paused.can_generate_slices());

        assert!(AlgoOrderState::Working.can_pause());
        assert!(AlgoOrderState::Paused.can_resume());
        assert!(!AlgoOrderState::Working.can_resume());

        assert!(AlgoOrderState::Filled.is_terminal());
        assert!(AlgoOrderState::Cancelled.is_terminal());
        assert!(AlgoOrderState::Rejected.is_terminal());
        assert!(AlgoOrderState::Expired.is_terminal());
        assert!(!AlgoOrderState::Working.is_terminal());
    }

    #[test]
    fn test_venue_id_index() {
        assert_eq!(VenueId::Internal.index(), 0);
        assert_eq!(VenueId::Cme.index(), 1);
        assert_eq!(VenueId::Nasdaq.index(), 2);
    }

    #[test]
    fn test_slice_state_terminal() {
        assert!(SliceState::Filled.is_terminal());
        assert!(SliceState::Cancelled.is_terminal());
        assert!(SliceState::Rejected.is_terminal());
        assert!(!SliceState::Pending.is_terminal());
        assert!(!SliceState::Sent.is_terminal());
    }
}
