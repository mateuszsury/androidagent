package dev.mateuszsury.androidagent;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded shell under the app UID, isolated from the Termux development workspace. */
final class LocalShell {
  static final int OUTPUT_LIMIT = 32768;
  private volatile Process process;
  private volatile boolean cancelled;
  private volatile File pidFile;

  static File workspace(Context c) {
    File f = new File(c.getFilesDir(), "workspace");
    if (!f.isDirectory() && !f.mkdirs())
      throw new IllegalStateException("Nie można utworzyć katalogu roboczego");
    return f;
  }

  static final class Result {
    final String output;
    final int exitCode;
    final boolean timedOut;

    Result(String o, int code, boolean timeout) {
      output = o;
      exitCode = code;
      timedOut = timeout;
    }

    String display() {
      return output + "\n\n[exit " + exitCode + (timedOut ? " · limit 10 s" : "") + "]";
    }
  }

  Result run(Context c, String command) throws Exception {
    if (command.trim().isEmpty()) throw new IllegalArgumentException("Wpisz komendę");
    if (command.length() > 8192) throw new IllegalArgumentException("Komenda jest za długa");
    pidFile = File.createTempFile("shell-", ".pid", c.getCacheDir());
    ProcessBuilder pb =
        new ProcessBuilder(
            "/system/bin/sh", "-c", "trap 'wait' EXIT; echo $$ > \"$ANDROIDAGENT_PID_FILE\"; " + command);
    pb.environment().put("ANDROIDAGENT_PID_FILE", pidFile.getAbsolutePath());
    pb.directory(workspace(c));
    pb.redirectErrorStream(true);
    Process p = null;
    try {
      p = pb.start();
      final Process running = p;
      process = p;
      p.getOutputStream().close();
      if (cancelled) stop();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      final boolean[] truncated = {false};
      Thread reader =
          new Thread(
              () -> {
                try (InputStream in = running.getInputStream()) {
                  byte[] buf = new byte[4096];
                  int n;
                  while ((n = in.read(buf)) != -1) {
                    synchronized (bytes) {
                      int take = Math.min(n, OUTPUT_LIMIT - bytes.size());
                      if (take > 0) bytes.write(buf, 0, take);
                      if (take < n) truncated[0] = true;
                    }
                  }
                } catch (IOException ignored) {
                }
              },
              "androidagent-shell-output");
      reader.setDaemon(true);
      reader.start();
      boolean finished = p.waitFor(10, TimeUnit.SECONDS);
      if (!finished) stop();
      reader.join(1200);
      String output;
      synchronized (bytes) {
        output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
      }
      if (truncated[0]) output += "\n[… wynik ograniczony do 32 KB]";
      return new Result(output, !finished ? 124 : cancelled ? 130 : p.exitValue(), !finished);
    } finally {
      if (p != null && p.isAlive()) stop();
      process = null;
      pidFile.delete();
    }
  }

  void stop() {
    cancelled = true;
    Process p = process;
    if (p != null) {
      try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
        int parent = Integer.parseInt(r.readLine());
        // Stop the shell before killing children: otherwise `wait` could return
        // and let the shell execute its next instruction during cancellation.
        android.os.Process.sendSignal(parent, 19);
        java.util.Set<Integer> killed = new java.util.HashSet<>();
        killChildren(parent, killed);
        // Android's Process implementation may map destroyForcibly to SIGTERM.
        // A stopped shell needs SIGKILL, which also handles TERM traps.
        android.os.Process.sendSignal(parent, 9);
        killed.add(parent);
        awaitKilled(killed);
      } catch (Exception ignored) {
      }
      p.destroyForcibly();
    }
  }

  /** SIGKILL delivery is asynchronous; do not return while a signalled child still runs. */
  private static void awaitKilled(java.util.Set<Integer> pids) {
    long deadline = android.os.SystemClock.elapsedRealtime() + 1000;
    while (!pids.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
      java.util.Iterator<Integer> iterator = pids.iterator();
      while (iterator.hasNext()) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + iterator.next() + "/stat"))) {
          String line = reader.readLine();
          if (line == null || line.substring(line.lastIndexOf(')') + 2).startsWith("Z ")) iterator.remove();
        } catch (IOException ignored) { iterator.remove(); }
      }
      if (!pids.isEmpty()) android.os.SystemClock.sleep(10);
    }
  }

  private static void killChildren(int parent, java.util.Set<Integer> killed) {
    File[] entries = new File("/proc").listFiles();
    if (entries == null) return;
    for (File entry : entries) {
      if (!entry.getName().matches("[0-9]+")) continue;
      try (BufferedReader reader = new BufferedReader(new FileReader(new File(entry, "stat")))) {
        String line = reader.readLine();
        String[] fields = line.substring(line.lastIndexOf(')') + 2).split(" ");
        if (Integer.parseInt(fields[1]) == parent) {
          int pid = Integer.parseInt(entry.getName());
          killChildren(pid, killed);
          killed.add(pid);
          android.os.Process.sendSignal(pid, 9);
        }
      } catch (Exception ignored) {
      }
    }
  }
}
