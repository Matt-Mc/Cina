# Android regression turnaround

## Observed bottleneck

The previous main run [40244697112](https://github.com/Matt-Mc/Cina/actions/runs/40244697112) took about 14.5 minutes. Its combined build/check step took 9m05s; emulator setup and instrumentation took 4m06s, including about 2m27s for the connected-test Gradle invocation. The earlier passing run [40242358569](https://github.com/Matt-Mc/Cina/actions/runs/40242358569) took about 18 minutes, including 12m44s in the build/check step. Native compilation is a larger target than removing test assertions.

## Changes

- `-Pemulator` now packages only x86_64. Ordinary phone and signed release builds still target arm64-v8a. Previously emulator CI compiled both architectures.
- JVM policy tests and lint run first with the existing `-PskipNative` option. Failures in those checks stop the job before native compilation or emulator startup.
- Gradle task-output caching is enabled. Native C/C++ compilation uses ccache in CI, with separate emulator and release caches and a pinned cache-action revision. CMake receives the compiler launcher through its standard environment variables. Compiler/source changes remain inputs to the compiler cache.
- The full deterministic Android suite still runs. Live-web and actual-model evaluations remain opt-in. No assertions or tests are removed, and failed tests are not automatically retried to produce a green result.
- The notification navigation test waits for the destination even if the Compose root is briefly absent. Its destination, approval-state and absence-of-side-effect assertions remain required. The previous main failure was traced to that polling condition at `WorkOverviewTest.kt:32`.

The first compiler-cache run is cold. A subsequent warm run is needed to measure cache benefit; local warm-build timings are not comparable to a fresh GitHub runner. Emulator setup still takes time. Do not promise a specific new duration before measuring the changed workflow.

## First measured runs

The first updated run [40258659560, attempt 1](https://github.com/Matt-Mc/Cina/actions/runs/40258659560/attempts/1) passed in 10m36s. Policy tests and lint took 1m36s; the emulator APK build took 3m57s. All enabled Android tests passed. The post-merge run and a repeat also passed, but took about 16 minutes and 15m16s respectively, so the initial total-time reduction was not consistent across runners.

The repeat restored the compiler cache but reported 0 hits out of 350 cacheable calls. Therefore no warm-cache speedup was established. Both workflows now use `CCACHE_COMPILERCHECK=content` to identify the actual compiler binary rather than installation timestamps. This avoids timestamp-based compiler identity changes while still invalidating on compiler changes. A subsequent populated-cache run must establish the real cross-run benefit; do not label the earlier repeat a successful warm-cache performance test.
