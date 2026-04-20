/// Trait for objects that can be stored in a pool.
pub trait Poolable: Default {
    /// Reset the object to its default state for reuse.
    fn reset(&mut self);
}

/// Fixed-capacity object pool with round-robin allocation.
///
/// Pre-allocates all objects up front; no heap allocation on acquire/release.
pub struct ObjectPool<T: Poolable> {
    objects: Vec<T>,
    index: usize,
}

impl<T: Poolable> ObjectPool<T> {
    /// Create a new pool with the given capacity, pre-allocating all objects.
    pub fn new(capacity: usize) -> Self {
        let mut objects = Vec::with_capacity(capacity);
        for _ in 0..capacity {
            objects.push(T::default());
        }
        Self { objects, index: 0 }
    }

    /// Acquire the next object from the pool (round-robin).
    ///
    /// Returns a mutable reference to the acquired object. The caller should
    /// initialize the object before use. The object is automatically recycled
    /// when the pool wraps around.
    pub fn acquire(&mut self) -> &mut T {
        let idx = self.index;
        self.index = (self.index + 1) % self.objects.len();
        let obj = &mut self.objects[idx];
        obj.reset();
        obj
    }

    /// Get a reference to an object by index (for direct access).
    pub fn get(&self, index: usize) -> &T {
        &self.objects[index]
    }

    /// Get a mutable reference to an object by index.
    pub fn get_mut(&mut self, index: usize) -> &mut T {
        &mut self.objects[index]
    }

    /// Pool capacity.
    pub fn capacity(&self) -> usize {
        self.objects.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct TestObj {
        value: u64,
        active: bool,
    }

    impl Poolable for TestObj {
        fn reset(&mut self) {
            self.value = 0;
            self.active = false;
        }
    }

    #[test]
    fn test_pool_basic() {
        let mut pool: ObjectPool<TestObj> = ObjectPool::new(4);
        assert_eq!(pool.capacity(), 4);

        let obj = pool.acquire();
        obj.value = 42;
        obj.active = true;
        assert_eq!(obj.value, 42);
    }

    #[test]
    fn test_pool_round_robin() {
        let mut pool: ObjectPool<TestObj> = ObjectPool::new(3);

        // Acquire all 3
        pool.acquire().value = 1;
        pool.acquire().value = 2;
        pool.acquire().value = 3;

        // Next acquire wraps around and resets
        let obj = pool.acquire();
        assert_eq!(obj.value, 0); // was reset
    }
}
