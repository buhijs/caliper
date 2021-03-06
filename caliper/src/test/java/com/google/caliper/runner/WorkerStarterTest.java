/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.caliper.runner;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.caliper.Benchmark;
import com.google.caliper.model.BenchmarkSpec;
import com.google.caliper.runner.config.VmConfig;
import com.google.caliper.runner.platform.JvmPlatform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests {@link WorkerStarter}.
 *
 * <p>TODO(lukes,gak): write more tests for how our specs get turned into commandlines
 */

@RunWith(JUnit4.class)
public class WorkerStarterTest {
  private static final int PORT_NUMBER = 4004;
  private static final UUID TRIAL_ID = UUID.randomUUID();

  private static class MockRegistrar implements ShutdownHookRegistrar {
    Set<Thread> hooks = Sets.newHashSet();

    @Override
    public void addShutdownHook(Thread hook) {
      hooks.add(hook);
    }

    @Override
    public boolean removeShutdownHook(Thread hook) {
      return hooks.remove(hook);
    }
  }

  private final MockRegistrar registrar = new MockRegistrar();
  private final WorkerStarter workerStarter = new LocalWorkerStarter(registrar);

  @Test
  public void simpleArgsTest() throws Exception {
    Method method = TestBenchmark.class.getDeclaredMethods()[0];
    AllocationInstrument allocationInstrument = new AllocationInstrument();
    allocationInstrument.setOptions(ImmutableMap.of("trackAllocations", "true"));
    VmConfig vmConfig =
        new VmConfig(
            new File("foo"), Arrays.asList("--doTheHustle"), new File("java"), new JvmPlatform());
    Experiment experiment =
        Experiment.create(
            allocationInstrument.createInstrumentedMethod(method),
            ImmutableMap.<String, String>of(),
            Target.create("foo-jvm", vmConfig));
    BenchmarkSpec spec =
        new BenchmarkSpec.Builder()
            .className(TestBenchmark.class.getName())
            .methodName(method.getName())
            .build();
    BenchmarkClass benchmarkClass = BenchmarkClass.forClass(TestBenchmark.class);
    Command command = createCommand(experiment, spec, benchmarkClass);
    List<String> commandLine = command.arguments();
    assertEquals(new File("java").getAbsolutePath(), commandLine.get(0));
    assertTrue(commandLine.contains("--doTheHustle"));
    assertTrue(commandLine.contains("-cp"));
    assertTrue(commandLine.containsAll(allocationInstrument.getExtraCommandLineArgs(vmConfig)));
    assertTrue(
        commandLine.containsAll(
            ImmutableSet.of("-XX:+PrintFlagsFinal", "-XX:+PrintCompilation", "-XX:+PrintGC")));
    // main class should be next to last, follower by the single arg to the worker
    assertEquals("com.google.caliper.worker.WorkerMain", commandLine.get(commandLine.size() - 2));
  }

  @Test
  public void shutdownHook_awaitExit() throws Exception {
    WorkerProcess worker = startWorker(FakeWorkers.Exit.class, "0");
    assertEquals(
        "worker-shutdown-hook-" + TRIAL_ID, Iterables.getOnlyElement(registrar.hooks).getName());
    worker.awaitExit();
    assertTrue(registrar.hooks.isEmpty());
  }

  @Test
  public void shutdownHook_kill() throws Exception {
    WorkerProcess worker =
        startWorker(FakeWorkers.Sleeper.class, Long.toString(MINUTES.toMillis(1)));
    worker.kill();
    assertTrue(registrar.hooks.isEmpty());
  }

  static final class TestBenchmark {
    @Benchmark
    long thing(long reps) {
      long dummy = 0;
      for (long i = 0; i < reps; i++) {
        dummy += new Long(dummy).hashCode();
      }
      return dummy;
    }
  }

  private Command createCommand(
      Experiment experiment,
      BenchmarkSpec benchmarkSpec,
      BenchmarkClass benchmarkClass) {
    WorkerSpec spec = new TrialSpec(
        TRIAL_ID, experiment, benchmarkSpec, benchmarkClass, 1, PORT_NUMBER);
    return WorkerModule.provideWorkerCommand(experiment.target(), spec);
  }

  private WorkerProcess startWorker(Class<?> main, String... args) throws Exception {
    return workerStarter.startWorker(TRIAL_ID, FakeWorkers.createCommand(main, args));
  }
}
