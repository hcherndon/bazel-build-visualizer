package com.holtherndon.bazelviz.capture.bes;

/** An immutable, exact view of one embedded BES server's bounded resources. */
public record BesResourceSnapshot(
    long retainedPayloadBytes,
    long retainedPayloadHighWaterBytes,
    long retainedPayloadLimitBytes,
    int activeRpcs,
    int activeRpcLimit,
    int admittedStreamKeys,
    int admittedStreamKeyLimit,
    int activeHandlerThreads,
    int queuedHandlerTasks,
    long rpcRefusals,
    long streamKeyRefusals,
    long handlerTaskRefusals) {}
