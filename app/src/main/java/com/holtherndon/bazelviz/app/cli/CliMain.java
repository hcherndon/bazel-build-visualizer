package com.holtherndon.bazelviz.app.cli;

import java.io.PrintStream;
import java.util.List;

/**
 * The headless entry point. {@link com.holtherndon.bazelviz.app.Main} hands
 * control here whenever it is given any argument at all, before it has touched
 * the application directories, the look and feel or the EDT — so a machine with
 * no display can run every subcommand and a run with arguments never opens a
 * window.
 *
 * <p>Nothing in this package imports a Swing or AWT type, and that is enforced
 * by the tests rather than by convention: {@code CliHeadlessTest} runs an
 * import with {@code java.awt.headless=true} and fails if any of it needs a
 * graphics environment.
 *
 * <p>{@link #run} returns the exit code instead of calling {@code System.exit}
 * so that tests can drive it in-process with captured streams. Only
 * {@code Main} turns the return value into a process exit.
 */
public final class CliMain {

    /** The name the tool calls itself in messages. */
    static final String PROGRAM = "bbv";

    private CliMain() {}

    /**
     * Runs one command line.
     *
     * @param args the process arguments, subcommand first
     * @param appVersion the version recorded in any session this run creates
     * @return the code to hand to {@code System.exit}; see {@link ExitCode}
     */
    public static int run(String[] args, PrintStream out, PrintStream err, String appVersion) {
        return run(List.of(args), CliContext.systemDefault(out, err, appVersion)).code();
    }

    static ExitCode run(List<String> args, CliContext ctx) {
        if (args.isEmpty()) {
            printHelp(ctx.out(), ctx.appVersion());
            return ExitCode.OK;
        }
        String command = args.get(0);
        List<String> rest = args.subList(1, args.size());
        try {
            return switch (command) {
                case "import" -> ImportCommand.run(rest, ctx);
                case "inspect" -> InspectCommand.run(rest, ctx);
                case "help", "--help" -> {
                    printHelp(ctx.out(), ctx.appVersion());
                    yield ExitCode.OK;
                }
                case "--version" -> {
                    ctx.out().println(PROGRAM + " " + ctx.appVersion());
                    ctx.out().flush();
                    yield ExitCode.OK;
                }
                default -> throw unknownCommand(command);
            };
        } catch (CliUsageException e) {
            // A mistyped flag is a conversation, not a defect: a message and a
            // pointer at the right --help, never a stack trace.
            ctx.err().println(PROGRAM + ": " + e.getMessage());
            ctx.err().println(e.helpHint());
            ctx.err().flush();
            return ExitCode.USAGE;
        } catch (RuntimeException e) {
            // A genuine defect. This one does print a trace, because there is no
            // user action that fixes it and the trace is the whole report.
            ctx.err().println(PROGRAM + ": unexpected failure in '" + command + "'");
            e.printStackTrace(ctx.err());
            ctx.err().flush();
            return ExitCode.FAILED;
        }
    }

    private static CliUsageException unknownCommand(String command) {
        String message = command.startsWith("-")
                ? "unknown option '" + command + "' before any subcommand"
                : "unknown command '" + command + "'";
        return new CliUsageException(message + "; the commands are 'import' and 'inspect'");
    }

    static void printHelp(PrintStream out, String appVersion) {
        out.println(PROGRAM + " — Bazel Build Visualizer " + appVersion);
        out.println();
        out.println("usage:");
        out.println("  bbv                                  launch the graphical application");
        out.println("  bbv import <bep-file> [options]      import a BEP capture into a session");
        out.println("  bbv inspect <session-dir> [options]  read back an imported session");
        out.println("  bbv --help | --version");
        out.println();
        out.println("With no arguments the graphical application starts. With a subcommand the");
        out.println("tool runs headless: it initializes no window toolkit and needs no display.");
        out.println();
        out.println("exit codes, the same for every subcommand:");
        out.println("  0  success");
        out.println("  1  the source was truncated or corrupt; everything before the damage was");
        out.println("     imported and the session it produced is real and usable");
        out.println("  2  the command line was wrong: unknown option, missing argument, or a");
        out.println("     path that is not there");
        out.println("  3  the command failed outright and produced no usable result");
        out.println("  4  interrupted; the session was left resumable ('bbv import … --resume')");
        out.println();
        out.println("run 'bbv import --help' or 'bbv inspect --help' for each command's options.");
        out.flush();
    }
}
