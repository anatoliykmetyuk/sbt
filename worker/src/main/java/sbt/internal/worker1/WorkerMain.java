/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal.worker1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Scanner;
import sbt.testing.*;

/**
 * WorkerMain that communicates via the stdin and stdout using JSON-RPC
 * (https://www.jsonrpc.org/specification).
 */
public final class WorkerMain {
  private PrintStream originalOut;
  private InputStream originalIn;
  private Scanner inScanner;

  public static Gson mkGson() {
    RuntimeTypeAdapterFactory<Fingerprint> fingerprintFac =
        RuntimeTypeAdapterFactory.of(Fingerprint.class, "type");
    fingerprintFac.registerSubtype(ForkTestMain.SubclassFingerscan.class, "SubclassFingerscan");
    fingerprintFac.registerSubtype(ForkTestMain.AnnotatedFingerscan.class, "AnnotatedFingerscan");
    RuntimeTypeAdapterFactory<Selector> selectorFac =
        RuntimeTypeAdapterFactory.of(Selector.class, "type");
    selectorFac.registerSubtype(SuiteSelector.class, "SuiteSelector");
    selectorFac.registerSubtype(TestSelector.class, "TestSelector");
    selectorFac.registerSubtype(NestedSuiteSelector.class, "NestedSuiteSelector");
    selectorFac.registerSubtype(NestedTestSelector.class, "NestedTestSelector");
    selectorFac.registerSubtype(TestWildcardSelector.class, "TestWildcardSelector");
    return new GsonBuilder()
        .registerTypeAdapterFactory(fingerprintFac)
        .registerTypeAdapterFactory(selectorFac)
        .registerTypeAdapterFactory(ThrowableAdapterFactory.INSTANCE)
        .create();
  }

  public static void main(final String[] args) throws Exception {
    try {
      if (args.length == 0) {
        WorkerMain app = new WorkerMain();
        app.consoleWork();
        System.exit(0);
      } else {
        System.err.println("missing args");
        System.exit(1);
      }
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  WorkerMain() {
    this.originalOut = System.out;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    System.setOut(new PrintStream(baos));
    this.originalIn = System.in;
    this.inScanner = new Scanner(this.originalIn);
  }

  void consoleWork() throws Exception {
    if (this.inScanner.hasNextLine()) {
      String line = this.inScanner.nextLine();
      process(line);
    }
  }

  /** This processes single request of supposed JSON line. */
  void process(String json) {
    JsonElement elem = JsonParser.parseString(json);
    JsonObject o = elem.getAsJsonObject();
    if (!o.has("jsonrpc")) {
      return;
    }
    Gson g = WorkerMain.mkGson();
    long id = o.getAsJsonPrimitive("id").getAsLong();
    try {
      String method = o.getAsJsonPrimitive("method").getAsString();
      JsonObject params = o.getAsJsonObject("params");
      switch (method) {
        case "run":
          RunInfo info = g.fromJson(params, RunInfo.class);
          run(info);
          break;
        case "test":
          TestInfo testInfo = g.fromJson(params, TestInfo.class);
          test(id, testInfo);
          break;
      }
      String response = String.format("{ \"jsonrpc\": \"2.0\", \"result\": 0, \"id\": %d }", id);
      this.originalOut.println(response);
      this.originalOut.flush();
    } catch (Throwable e) {
      WorkerError err = new WorkerError(1, e.getMessage());
      String errMessage = g.toJson(err, err.getClass());
      String errJson =
          String.format("{ \"jsonrpc\": \"2.0\", \"error\": %s, \"id\": %d }", errMessage, id);
      this.originalOut.println(errJson);
      this.originalOut.flush();
    }
  }

  void run(RunInfo info) throws Exception {
    if (info.jvm) {
      if (info.jvmRunInfo == null) {
        throw new RuntimeException("missing jvmRunInfo element");
      }
      RunInfo.JvmRunInfo jvmRunInfo = info.jvmRunInfo;
      try (URLClassLoader cl = createClassLoader(jvmRunInfo, ClassLoader.getSystemClassLoader())) {
        Class<?> mainClass = cl.loadClass(jvmRunInfo.mainClass);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        String[] mainArgs = jvmRunInfo.args.stream().toArray(String[]::new);
        mainMethod.invoke(null, (Object) mainArgs);
      }
    } else {
      throw new RuntimeException("only jvm is supported");
    }
  }

  void test(long id, TestInfo info) throws Exception {
    if (info.jvm) {
      RunInfo.JvmRunInfo jvmRunInfo = info.jvmRunInfo;
      ClassLoader parent = ClassLoader.getSystemClassLoader();
      try (URLClassLoader cl = createClassLoader(jvmRunInfo, parent)) {
        ForkTestMain.main(id, info, this.originalOut, cl);
      }
    } else {
      throw new RuntimeException("only jvm is supported");
    }
  }

  private URLClassLoader createClassLoader(RunInfo.JvmRunInfo info, ClassLoader parent) {
    URL[] urls =
        info.classpath
            .stream()
            .map(
                filePath -> {
                  try {
                    return filePath.path.toURL();
                  } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                  }
                })
            .toArray(URL[]::new);
    // Use filtering classloader to prevent test code from accessing sbt's Gson and Framework
    // from system classloader, while allowing ForkTestMain to load Framework for test execution.
    // This ensures test code uses project's dependencies from classpath instead of .sbt/boot.
    ClassLoader filteringParent = new FilteringClassLoader(parent);
    return new URLClassLoader(urls, filteringParent);
  }

  /**
   * A classloader that filters out sbt's internal dependencies (Gson, Framework/test-interface)
   * from the system classloader, preventing test code from accessing them. This ensures test code
   * uses the project's dependencies from the classpath instead of sbt's dependencies from
   * .sbt/boot.
   *
   * <p>Framework classes are conditionally allowed: they can be loaded when called from
   * ForkTestMain (sbt.internal.worker1 package) to enable test framework loading, but are filtered
   * out when called from test code to prevent .sbt/boot leak.
   *
   * <p>For non-filtered classes, this classloader delegates to the system classloader normally.
   */
  private static class FilteringClassLoader extends ClassLoader {
    private final ClassLoader systemLoader;

    FilteringClassLoader(ClassLoader systemLoader) {
      super(null); // no parent - we manually delegate
      this.systemLoader = systemLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      // #region agent log
      try {
        String logEntry =
            String.format(
                "{\"id\":\"log_%d_001\",\"timestamp\":%d,\"location\":\"WorkerMain.java:195\",\"message\":\"FilteringClassLoader.loadClass called\",\"data\":{\"className\":\"%s\",\"resolve\":%s},\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\"A,B,C\"}\n",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                name.replace("\"", "\\\""),
                resolve);
        java.nio.file.Files.write(
            java.nio.file.Paths.get("/Users/anatolii/Projects/STA/sbt/.cursor/debug.log"),
            logEntry.getBytes("UTF-8"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
      } catch (Exception e) {
      }
      // #endregion

      // Filter out Gson classes - always filter, don't delegate to system classloader
      // This prevents test code from accessing sbt's Gson
      if (name.startsWith("com.google.gson.")) {
        // #region agent log
        try {
          java.nio.file.Files.write(
              java.nio.file.Paths.get("/Users/anatolii/Projects/STA/sbt/.cursor/debug.log"),
              String.format(
                      "{\"id\":\"log_%d_002\",\"timestamp\":%d,\"location\":\"WorkerMain.java:201\",\"message\":\"Gson class filtered\",\"data\":{\"className\":\"%s\"},\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\"A\"}\n",
                      System.currentTimeMillis(), System.currentTimeMillis(), name)
                  .getBytes("UTF-8"),
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
        }
        // #endregion
        throw new ClassNotFoundException(name + " filtered out (use project's Gson instead)");
      }

      // For Framework/test-interface classes, check if caller is from ForkTestMain
      if (name.startsWith("sbt.testing.") || name.startsWith("org.scalatools.testing.")) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // #region agent log
        try {
          java.util.List<String> stackClasses = new java.util.ArrayList<>();
          for (int i = 0; i < stack.length && i < 15; i++) {
            stackClasses.add(
                stack[i].getClassName()
                    + "."
                    + stack[i].getMethodName()
                    + ":"
                    + stack[i].getLineNumber());
          }
          String stackJson = new Gson().toJson(stackClasses);
          String logEntry =
              String.format(
                  "{\"id\":\"log_%d_003\",\"timestamp\":%d,\"location\":\"WorkerMain.java:208\",\"message\":\"Framework class access detected\",\"data\":{\"className\":\"%s\",\"stackTrace\":%s,\"stackLength\":%d},\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\"A,B,C\"}\n",
                  System.currentTimeMillis(),
                  System.currentTimeMillis(),
                  name.replace("\"", "\\\""),
                  stackJson,
                  stack.length);
          java.nio.file.Files.write(
              java.nio.file.Paths.get("/Users/anatolii/Projects/STA/sbt/.cursor/debug.log"),
              logEntry.getBytes("UTF-8"),
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
        }
        // #endregion

        // Check if any caller is from sbt.internal.worker1 package (ForkTestMain, WorkerMain, etc.)
        boolean calledFromWorker = false;
        java.util.List<String> checkedClasses = new java.util.ArrayList<>();
        // Skip frames 0-1 (getStackTrace, loadClass) and check callers
        // Need to check more frames (up to 20) because the call path goes through multiple
        // ClassLoader methods before reaching ForkTestMain
        for (int i = 2; i < stack.length && i < 20; i++) {
          String className = stack[i].getClassName();
          checkedClasses.add(className);
          // Allow if called from sbt.internal.worker1 package (ForkTestMain needs Framework)
          if (className.startsWith("sbt.internal.worker1.")) {
            calledFromWorker = true;
            // #region agent log
            try {
              String checkedJson = new Gson().toJson(checkedClasses);
              String logEntry =
                  String.format(
                      "{\"id\":\"log_%d_004\",\"timestamp\":%d,\"location\":\"WorkerMain.java:220\",\"message\":\"Framework access allowed from worker\",\"data\":{\"className\":\"%s\",\"caller\":\"%s\",\"frameIndex\":%d,\"checkedClasses\":%s},\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\"A\"}\n",
                      System.currentTimeMillis(),
                      System.currentTimeMillis(),
                      name.replace("\"", "\\\""),
                      className.replace("\"", "\\\""),
                      i,
                      checkedJson);
              java.nio.file.Files.write(
                  java.nio.file.Paths.get("/Users/anatolii/Projects/STA/sbt/.cursor/debug.log"),
                  logEntry.getBytes("UTF-8"),
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
            }
            // #endregion
            break;
          }
        }

        if (!calledFromWorker) {
          // #region agent log
          try {
            String checkedJson = new Gson().toJson(checkedClasses);
            String logEntry =
                String.format(
                    "{\"id\":\"log_%d_005\",\"timestamp\":%d,\"location\":\"WorkerMain.java:232\",\"message\":\"Framework class filtered - not from worker\",\"data\":{\"className\":\"%s\",\"checkedClasses\":%s},\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\"B,C\"}\n",
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    name.replace("\"", "\\\""),
                    checkedJson);
            java.nio.file.Files.write(
                java.nio.file.Paths.get("/Users/anatolii/Projects/STA/sbt/.cursor/debug.log"),
                logEntry.getBytes("UTF-8"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
          } catch (Exception e) {
          }
          // #endregion
          // Filter out Framework when called from test code
          throw new ClassNotFoundException(
              name + " filtered out (use project's test-interface instead)");
        }
        // #region agent log
        try {
          String logEntry =
              String.format(
                  "{\"id\":\"log_%d_006\",\"timestamp\":%d,\"location\":\"WorkerMain.java:240\",\"message\":\"Framework class allowed - delegating to system classloader\",\"data\":{\"className\":\"%s\"},\"sessionId\":\"debug-session\",\"runId\":\"pre-fix\",\"hypothesisId\":\"A\"}\n",
                  System.currentTimeMillis(),
                  System.currentTimeMillis(),
                  name.replace("\"", "\\\""));
          java.nio.file.Files.write(
              java.nio.file.Paths.get("/Users/anatolii/Projects/STA/sbt/.cursor/debug.log"),
              logEntry.getBytes("UTF-8"),
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
        }
        // #endregion
        // Allow Framework when called from ForkTestMain - delegate to system classloader
      }

      // For all other classes, delegate to system classloader
      Class<?> c = systemLoader.loadClass(name);
      if (resolve) {
        resolveClass(c);
      }
      return c;
    }
  }
}
