package com.holtherndon.bazelviz.ui.capture;

import java.util.Objects;

/** Stable and user-readable identity for the workspace holding a capture lease. */
public record CaptureLeaseOwner(String ownerId, String displayName) {

  public CaptureLeaseOwner {
    ownerId = required(ownerId, "owner id");
    displayName = required(displayName, "owner display name");
  }

  private static String required(String value, String label) {
    String checked = Objects.requireNonNull(value, label).strip();
    if (checked.isEmpty()) {
      throw new IllegalArgumentException(label + " is required");
    }
    if (checked.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + " cannot contain control text");
    }
    return checked;
  }
}
