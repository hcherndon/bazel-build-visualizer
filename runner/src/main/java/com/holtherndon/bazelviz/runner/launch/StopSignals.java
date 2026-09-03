package com.holtherndon.bazelviz.runner.launch;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;

/**
 * Delivers one rung of the cancellation ladder to a Bazel client.
 *
 * <p>A seam, and one the project earned. The three rungs differ only in which operating-system
 * signal reaches the client, and the gentlest of them is sent by running {@code /bin/kill} — so the
 * only way to assert that Cancel sends {@code SIGINT} and not {@code SIGTERM} used to be to start a
 * real Bazel and watch it die. That is a test nobody runs, which is why the escalation ladder could
 * contradict its own documentation without a single test failing.
 *
 * <p>With this interface a test supplies a recorder, drives {@link
 * BazelLauncher.BazelProcess#cancel} against a scripted process, and asserts on the exact sequence
 * of rungs that went out. The production implementation is {@link OsStopSignals} and is the only
 * one the launcher ever builds for itself.
 */
interface StopSignals {

  /**
   * Delivers {@code mode} to {@code process}.
   *
   * <p>Called at most once per rung, under the process's stop lock, so an implementation does not
   * have to be re-entrant. It must not wait for the process to exit: the caller owns the grace
   * period, and a delivery that blocked would make the next, harsher rung unreachable for as long
   * as it blocked.
   */
  void deliver(CancellationMode mode, Process process);
}
