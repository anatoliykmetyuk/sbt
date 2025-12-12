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
    // Use child-first classloader to ensure project dependencies are loaded from classpath
    // before parent (which may have .sbt/boot jars)
    return new ChildFirstURLClassLoader(urls, parent);
  }

  /**
   * A URLClassLoader that uses child-first delegation: it checks its own URLs before delegating to
   * the parent. This ensures project dependencies from the classpath are loaded instead of versions
   * from the parent classloader (e.g., .sbt/boot jars).
   */
  private static class ChildFirstURLClassLoader extends URLClassLoader {
    private final ClassLoader parent;

    ChildFirstURLClassLoader(URL[] urls, ClassLoader parent) {
      super(urls, parent);
      this.parent = parent;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      // First, check if the class has already been loaded
      Class<?> c = findLoadedClass(name);
      if (c != null) {
        if (resolve) resolveClass(c);
        return c;
      }

      // Try to find the class in this classloader's URLs first (child-first)
      try {
        c = findClass(name);
        if (resolve) resolveClass(c);
        return c;
      } catch (ClassNotFoundException e) {
        // Class not found in this classloader, delegate to parent
        if (parent != null) {
          return parent.loadClass(name);
        }
        throw e;
      }
    }
  }
}
