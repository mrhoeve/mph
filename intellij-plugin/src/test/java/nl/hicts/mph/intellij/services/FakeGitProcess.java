package nl.hicts.mph.intellij.services;

import java.nio.file.Path;
import java.util.List;

public final class FakeGitProcess {
    private FakeGitProcess() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stdin" -> {
                while (System.in.read() != -1) {
                    // Drain until the runner closes the process input.
                }
                System.out.print("stdin closed");
            }
            case "output" -> {
                for (int i = 0; i < 256; i++) {
                    System.out.print("o".repeat(8192));
                    System.err.print("e".repeat(8192));
                }
            }
            case "wait" -> {
                Thread.sleep(800);
                System.out.print("finished");
            }
            case "child" -> Thread.sleep(8000);
            case "descendant" -> {
                Process child = new ProcessBuilder(
                    javaExecutable(), "-cp", System.getProperty("java.class.path"), FakeGitProcess.class.getName(), "child"
                ).inheritIO().start();
                System.out.print(child.pid());
            }
            case "environment" -> System.out.print(
                List.of("GIT_DIR", "GIT_INDEX_FILE", "GIT_WORK_TREE", "GIT_TERMINAL_PROMPT").stream()
                    .map(name -> String.valueOf(System.getenv(name)))
                    .reduce((left, right) -> left + ":" + right)
                    .orElse("")
            );
            default -> throw new IllegalArgumentException("Unsupported test mode");
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
