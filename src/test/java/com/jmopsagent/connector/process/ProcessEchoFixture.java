package com.jmopsagent.connector.process;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProcessEchoFixture {
    private ProcessEchoFixture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("sleep")) {
            Thread.sleep(30_000);
            return;
        }
        if (args.length > 2 && args[0].equals("spawn-child")) {
            Process child = new ProcessBuilder(args[1], "-cp", System.getProperty("java.class.path"),
                    ProcessEchoFixture.class.getName(), "sleep")
                    .inheritIO()
                    .start();
            String pids = ProcessHandle.current().pid() + System.lineSeparator() + child.pid();
            Files.writeString(Path.of(args[2]), pids, StandardCharsets.UTF_8);
            Thread.sleep(30_000);
            return;
        }
        if (args.length > 1 && args[0].equals("environment")) {
            System.out.print(System.getenv(args[1]));
            return;
        }
        String stdin = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.print("arg=" + (args.length == 0 ? "" : args[0]) + ";stdin=" + stdin);
        System.err.print("fixture-stderr");
    }
}
