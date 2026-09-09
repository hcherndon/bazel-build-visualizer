# Standalone pprof files

Choose **File › Open pprof…** and select a local pprof file. No Workspace,
Bazel invocation, or build session is required. Each file opens in its own
normal window; **Cmd+W** on macOS or **Ctrl+W** elsewhere closes that viewer.

The viewer shares the Starlark Profile page's **Summary**, searchable and paged
**Hot Functions** and **Files**, directed **Call Graph**, and **Flame** views.
Graph boxes default to self weight; they can be moved, and the weight can be
changed to cumulative or uniform. Hover details appear immediately.

## Measurements

Both gzip-compressed and uncompressed protobuf profiles are accepted. The
viewer uses `default_sample_type`, or the last sample type when none is named,
following the [pprof format](https://github.com/google/pprof/blob/main/proto/profile.proto).
The Summary identifies that type and its original unit. CPU nanoseconds, heap
bytes, allocation counts, and custom units remain distinct; no conversion to
Bazel CPU microseconds is performed. Values are displayed in the recorded
unit, including graph labels and tooltips.

Self values belong to the sampled leaf; cumulative values include callees.
Do not add cumulative values across functions. A sample record may aggregate
many observations. Missing symbols are not zero work: the attribution cards
report known and unattributed values, and bounded graph views state their
omitted counts. The profile is not associated with the current build.

**Open source** asks you to locate the file on this machine. Recorded paths
can refer to another machine; the viewer does not automatically connect to it,
download symbols, or run commands from a profile.

## Storage and current limits

Opening and querying happen in background workers. Data is streamed into a
private temporary SQLite database after a bounded copy of the original file,
not retained as a complete Java object
graph. The original file is never modified. Closing the viewer removes its
temporary database and source copy; an application crash may leave them in the OS temporary
directory. Profiles are not added to Recent Sessions or saved to the library.

The existing [profile import and rendering limits](limits.md) also apply here.
Malformed, over-limit, negative-valued (in the selected metric), or overflowing profiles report an
error instead of displaying partial results as complete.

This first viewer uses only the default measurement. Switching sample types,
signed/difference profiles, automatic symbolization, legacy text profile
formats, and filtering by sample labels are not yet supported. Raw sample
types, values, labels, and inline frames remain in the temporary database;
ambiguous physical call contexts retain the Starlark viewer's explicit
attribution limits. This is a shared visual explorer, not a replacement for
every pprof CLI feature.
