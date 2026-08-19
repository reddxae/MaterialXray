package com.material.xray.model

internal enum class ConnectionProgress {
    PreparingRuntime,
    PreparingCore,
    UpdatingRoutingData,
    ResolvingEntryServer,
    GeneratingConfiguration,
    StartingCore,
    ConfiguringTunnel,
    ConfiguringRouting,
    WaitingForCore,
    StoppingCore,
    CleaningRuntime,
    InspectingSavedRuntime,
    VerifyingRuntime,
    RestoringControlApi,
    WaitingForNetwork,
    UpdatingNetworkRoute,
    UpdatingAppRouting,
}
