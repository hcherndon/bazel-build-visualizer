/**
 * The offline BEP import pipeline: the component that turns a capture file into a managed session.
 *
 * <p>{@link com.holtherndon.bazelviz.capture.file.importer.BepImporter} is the entry point and
 * documents the order of operations. The rest of the package is what that order needs: {@link
 * com.holtherndon.bazelviz.capture.file.importer.SourcePreservation} and {@link
 * com.holtherndon.bazelviz.capture.file.importer.SourceDigest} for keeping the original, {@link
 * com.holtherndon.bazelviz.capture.file.importer.SourceCheckpoint} for the source-side half of a
 * resume point, and {@link com.holtherndon.bazelviz.capture.file.importer.JournalPayloadReader} for
 * getting the raw bytes back out again.
 *
 * <p>How a payload becomes rows lives one package over, in {@link
 * com.holtherndon.bazelviz.capture.normalize.EventNormalizer}. It moved there in Phase 2, when live
 * BES capture became a second producer of the same rows: keeping one definition is what makes a
 * captured session and an imported session agree, and leaving it in a package named {@code
 * importer} would have invited a second copy for the capture path.
 *
 * <p>Everything here is blocking I/O and SQL and must run off the Swing EDT. The package
 * deliberately names no Swing type; a UI observes an import through {@link
 * com.holtherndon.bazelviz.capture.file.importer.ImportProgressListener}.
 */
package com.holtherndon.bazelviz.capture.file.importer;
