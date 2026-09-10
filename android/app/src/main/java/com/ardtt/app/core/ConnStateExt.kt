package com.ardtt.app.core

fun ConnState.holdsUserSession(): Boolean = when (this) {
    ConnState.Connecting,
    ConnState.Connected,
    ConnState.PausedTrustedWifi,
    ConnState.WaitingForNetwork,
    ConnState.Recovering,
    ConnState.CaptivePortal,
    ConnState.NeedsUserAction,
    ConnState.Disconnecting,
    -> true
    ConnState.Idle,
    ConnState.Probing,
    ConnState.Ready,
    ConnState.Error,
    -> false
}

fun ConnState.disconnectsOnPowerClick(): Boolean = when (this) {
    ConnState.Connected,
    ConnState.Connecting,
    ConnState.Disconnecting,
    ConnState.PausedTrustedWifi,
    ConnState.WaitingForNetwork,
    ConnState.Recovering,
    ConnState.CaptivePortal,
    ConnState.NeedsUserAction,
    -> true
    else -> false
}

fun ConnState.isConfirmedConnected(): Boolean = this == ConnState.Connected

fun ConnState.blocksProfileSwitch(): Boolean = holdsUserSession()
