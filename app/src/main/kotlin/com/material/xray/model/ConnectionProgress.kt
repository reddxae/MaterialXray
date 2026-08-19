package com.material.xray.model

internal enum class ConnectionProgress {
    PreparingRuntime,
    PreparingCore,
    UpdatingRoutingData,
    DetectingNetworkRoute,
    ResolvingEntryServer,
    GeneratingConfiguration,
    StartingCore,
    ConfiguringTunnel,
    ConfiguringRouting,
    WaitingForCore,
}
