# Performance harness and baseline profiles

Lumora ships a macrobenchmark module and a `benchmark` build type so performance work is
measured on a real device rather than guessed at. Neither is part of a release APK: the
`benchmark` applicationId (`com.lumora.benchmark`) installs beside the real app, and the seed
activity exists only in `app/src/benchmark`.

## What is measured

| Benchmark | Metric | What it answers |
|---|---|---|
| `StartupBenchmark.coldStartupNoAot` | `StartupTimingMetric` with `CompilationMode.None()` | worst-case cold start, most sensitive to a baseline profile |
| `StartupBenchmark.coldStartupPartial` | `StartupTimingMetric` with `CompilationMode.Partial()` | cold start on a normal install |
| `LiveScrollBenchmark.liveScroll` | `FrameTimingMetric` | D-pad scroll through 20k live channels |
| `LiveScrollBenchmark.filmsShelfAndGrid` | `FrameTimingMetric` | VOD shelves and poster rows |
| `ChannelZapBenchmark.zapBetweenLiveChannels` | `FrameTimingMetric` + `TraceSectionMetric("LumoraZap")` | channel-change latency and dropped frames |

`LumoraZap` is an async atrace section opened by `PlayerManager.playUrl` and closed on the
first rendered frame (or the first error). On API &lt; 29 `TraceCompat` makes it a no-op.

## Seed fixture

`BenchmarkCatalog` generates 20,000 live channels, 2,000 films and 1,000 series, plus 300
channels of EPG. It is written through the same `CatalogRepository.replaceProvider` path a
real provider uses, so the benchmark exercises the production Room catalogue and derive
pipelines. The provider stamp (`catalog_refreshed_at`) is set to "now", so the launch path
treats the catalogue as fresh and does not attempt a network refresh.

URLs point at `benchmark.invalid` on purpose: browsing benchmarks must not touch the network.
A player benchmark therefore uses the seeded rows for navigation only; measure real playback
with a panel you are authorised to use.

## Running it

```bash
# 1. Build both APKs (the app under test and the test APK).
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark

# 2. Install them on a device you control.
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk

# 3. Seed once (the test setup block also does this automatically).
adb shell am start -W -n \
  com.lumora.benchmark/com.lumora.benchmark.BenchmarkSeedActivity
# wait until the activity says SEEDED

# 4. Run a benchmark. Verify the exact task name once with:
#    ./gradlew :benchmark:tasks --all
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lumora.macrobenchmark.StartupBenchmark
```

Run each benchmark at least three times and record the median. Compare results from the same
device, build type and power state; a Fire TV Stick plugged into a charger behaves differently
from one on a power-saving HDMI port.

## Generating baseline profiles

Profiles are generated on a device, not in CI, then committed:

```bash
./gradlew :app:generateBaselineProfile
```

or run `BaselineProfileGenerator` from Android Studio with the `benchmark` build type
selected. The plugin writes:

- `app/src/main/generated/baselineProfiles/baseline-prof.txt`
- `app/src/main/generated/baselineProfiles/startup-prof.txt`

Commit both. `mergeIntoMain = true` makes release builds merge them; `profileinstaller` applies
the profile on first run. A release build still succeeds when the files are absent, so a fresh
clone is not blocked before its first device run.

## Rules

- Never install the `benchmark` build on a device you do not control; it seeds a fake provider.
- Never add the seed activity to `app/src/main`; it must stay in `app/src/benchmark`.
- Keep the catalogue deterministic. Changing counts or names invalidates comparisons and
  the derived caches.
- A profile generated on one device helps another, but measure the target device separately.
