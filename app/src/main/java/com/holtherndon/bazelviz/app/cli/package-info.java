/**
 * The headless command line: {@code bbv import} and {@code bbv inspect}.
 *
 * <p>This package is the testing and scripting face of the Phase 1 pipeline and
 * the way a developer verifies an import without the GUI. Nothing here may
 * reference Swing, AWT or the EDT, directly or transitively: CI runs these
 * commands on machines with no display, and the moment a window toolkit is
 * touched the tool stops being usable there.
 *
 * <p>{@link com.holtherndon.bazelviz.app.Main} dispatches into
 * {@link com.holtherndon.bazelviz.app.cli.CliMain} before it does anything
 * else, so an invocation with arguments never initializes the application
 * directories, the look and feel or the window.
 */
package com.holtherndon.bazelviz.app.cli;
