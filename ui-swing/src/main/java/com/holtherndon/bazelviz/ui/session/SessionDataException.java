package com.holtherndon.bazelviz.ui.session;

/**
 * A read against an open session failed.
 *
 * <p>Unchecked because the read path it guards is {@link
 * com.holtherndon.bazelviz.ui.table.RowSource#fetchPage}, whose signature declares no checked
 * exception: {@code PagedTableModel} catches whatever a fetch throws, records the page as failed
 * and renders its rows as {@code ⚠} rather than as a page that is merely still loading. Turning a
 * failure into an empty page here instead would be a silent drop — the user would see a table that
 * looks complete and is not.
 */
public class SessionDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SessionDataException(String message) {
    super(message);
  }

  public SessionDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
