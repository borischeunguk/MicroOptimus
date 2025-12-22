# Running Boost circular_buffer Test in CLion

This guide shows you how to run the Boost circular_buffer test separately in CLion, both from GUI and command line.

---

## Method 1: Using CLion GUI (Recommended)

### Step 1: Reload CMake Project
1. Open CLion
2. Go to **File** → **Reload CMake Project**
3. Or click the "Reload CMake Project" icon in the CMake tool window

### Step 2: Configure CMake with Boost Enabled
1. Go to **File** → **Settings** (or **CLion** → **Preferences** on macOS)
2. Navigate to **Build, Execution, Deployment** → **CMake**
3. In the **CMake options** field, add:
   ```
   -DUSE_BOOST=ON -DBUILD_TESTING=ON
   ```
4. Click **OK**
5. CLion will automatically reload the CMake project

### Step 3: Run the Test from GUI

**Option A: Using Run Configurations**
1. Click the run configuration dropdown (top right)
2. Select **boost_circular_buffer_test**
3. Click the green Run button ▶️ (or press Shift+F10)

**Option B: Using CMake Tool Window**
1. Open **CMake** tool window (View → Tool Windows → CMake)
2. Expand the project tree
3. Navigate to: **liquidator** → **src** → **main** → **cpp**
4. Right-click **boost_circular_buffer_test**
5. Select **Run 'boost_circular_buffer_test'**

**Option C: Using CTest**
1. Open **CMake** tool window
2. Click on the **CTest** tab at the bottom
3. Find and click **boost_circular_buffer_test**
4. Click the green Run button

**Option D: From Test Explorer**
1. Open **Run** → **Run...** (or press Alt+Shift+F10)
2. Select **boost_circular_buffer_test**
3. The test will run and show results

### Step 4: View Test Results
- Test output appears in the **Run** tool window
- Green checkmarks ✓ indicate passing tests
- Failed tests will show in red with details

---

## Method 2: Using CLion Terminal

### From Built-in Terminal
1. Open CLion's Terminal (View → Tool Windows → Terminal)
2. Navigate to build directory:
   ```bash
   cd build_with_boost/liquidator/src/main/cpp
   ```
3. Run the test:
   ```bash
   ./boost_circular_buffer_test
   ```

### With Verbose Output
```bash
./boost_circular_buffer_test --gtest_color=yes --gtest_brief=0
```

### Run Specific Test
```bash
# Run only performance test
./boost_circular_buffer_test --gtest_filter=*Performance*

# Run only construction tests
./boost_circular_buffer_test --gtest_filter=*Construction*

# List all available tests
./boost_circular_buffer_test --gtest_list_tests
```

---

## Method 3: Creating Custom Run Configuration

### Create a Custom Configuration
1. Go to **Run** → **Edit Configurations...**
2. Click the **+** button and select **CMake Application**
3. Configure:
   - **Name:** `Boost Circular Buffer Test`
   - **Target:** `boost_circular_buffer_test`
   - **Executable:** (auto-filled)
   - **Program arguments:** (optional, e.g., `--gtest_color=yes`)
   - **Working directory:** (auto-filled)
4. Click **OK**

### Add GTest Parameters (Optional)
In **Program arguments**, you can add:
```
--gtest_color=yes --gtest_brief=0
```

Or for filtering:
```
--gtest_filter=BoostCircularBufferTest.Performance*
```

---

## Method 4: Using Build Script

### From CLion Terminal
```bash
# Build and run all tests including circular_buffer
./build_sor_with_boost.sh
```

This script:
1. Checks for Boost installation
2. Configures CMake with Boost enabled
3. Builds all components
4. Runs all tests automatically

---

## Troubleshooting

### Issue: Test Not Showing Up

**Solution 1: Ensure Boost is Enabled**
```bash
# In CLion terminal
cd build_with_boost
cmake .. -DUSE_BOOST=ON -DBUILD_TESTING=ON
cmake --build .
```

**Solution 2: Check CMake Output**
Look for this message in CMake output:
```
-- Boost circular_buffer test enabled
```

If you see:
```
-- Boost circular_buffer test disabled
```

Then Boost was not found. Install it:
```bash
# macOS
brew install boost

# Linux
sudo apt-get install libboost-all-dev
```

### Issue: Boost Not Found

**Check Boost Installation:**
```bash
# macOS
brew list boost

# Linux
dpkg -l | grep boost
```

**Set BOOST_ROOT Manually:**
```bash
# In CMake options
-DBOOST_ROOT=/usr/local/opt/boost
```

### Issue: Build Errors

**Clean and Rebuild:**
1. Delete build directory: `rm -rf build_with_boost`
2. Reload CMake project
3. Rebuild

---

## Running Individual Test Cases

### From GUI
1. Open `test_boost_circular_buffer.cpp` in editor
2. Click the green ▶️ icon next to any `TEST_F` declaration
3. Select **Run** or **Debug**

### From Command Line
```bash
# Run only ConstructionAndCapacity test
./boost_circular_buffer_test --gtest_filter=BoostCircularBufferTest.ConstructionAndCapacity

# Run all tests except performance
./boost_circular_buffer_test --gtest_filter=-*Performance*

# Run with verbose output
./boost_circular_buffer_test --gtest_filter=BoostCircularBufferTest.PerformanceInsertion -v
```

---

## Debugging the Test

### Using CLion Debugger
1. Set breakpoints in `test_boost_circular_buffer.cpp`
2. Right-click the test configuration
3. Select **Debug 'boost_circular_buffer_test'**
4. Or press Shift+F9

### Debug Specific Test
1. Open `test_boost_circular_buffer.cpp`
2. Click the bug icon 🐛 next to the test you want to debug
3. Debugger will stop at breakpoints

---

## Viewing Test Coverage (Optional)

### Enable Coverage
1. **Run** → **Run with Coverage**
2. Or right-click test → **Run 'boost_circular_buffer_test' with Coverage**

### View Results
- Coverage report appears in **Coverage** tool window
- Shows which lines were executed during tests

---

## Quick Reference Commands

### Build Only
```bash
cd build_with_boost
cmake --build . --target boost_circular_buffer_test
```

### Run with CTest
```bash
cd build_with_boost
ctest -R boost_circular_buffer_test -V
```

### Run with Detailed Output
```bash
cd build_with_boost/liquidator/src/main/cpp
./boost_circular_buffer_test --gtest_color=yes --gtest_print_time=1
```

### List All Tests
```bash
./boost_circular_buffer_test --gtest_list_tests
```

### Run and Save Output
```bash
./boost_circular_buffer_test --gtest_output=xml:test_results.xml
```

---

## Integration with Other Tests

### Run All Tests
```bash
# From project root
./build_sor_with_boost.sh

# Or manually
cd build_with_boost
ctest --output-on-failure
```

### Run Just SOR Tests
```bash
cd build_with_boost/liquidator/src/main/cpp
./sor_unit_tests
```

### Run Performance Tests
```bash
./sor_perf_test
```

---

## Tips for Best Experience

1. **Keep CMake options persistent:**
   - Set `-DUSE_BOOST=ON` in CLion settings permanently
   - This ensures Boost tests are always available

2. **Use keyboard shortcuts:**
   - Shift+F10: Run
   - Shift+F9: Debug
   - Ctrl+Shift+F10: Run context configuration

3. **Watch CMake output:**
   - Check "Messages" tab in CMake tool window
   - Verify "Boost circular_buffer test enabled" appears

4. **Use test filters:**
   - Run specific tests faster during development
   - Use `--gtest_filter` for targeted testing

---

## Example Workflow

### Daily Development
1. Open CLion
2. Make changes to test or SOR code
3. Press Shift+F10 to run tests
4. View results immediately
5. Debug if needed with Shift+F9

### Continuous Integration
1. Run `build_sor_with_boost.sh` from terminal
2. All tests run automatically
3. View summary report
4. Check individual test outputs if needed

---

## Need Help?

- **Documentation:** See `README_BOOST_CIRCULAR_BUFFER.md`
- **Build Guide:** See `CMAKE_BUILD_GUIDE.md`
- **Test Details:** Open `test_boost_circular_buffer.cpp`

For more information about Google Test flags:
```bash
./boost_circular_buffer_test --help
```

