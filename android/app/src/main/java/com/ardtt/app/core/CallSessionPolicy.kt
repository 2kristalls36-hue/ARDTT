package com.ardtt.app.core

/**
 * When a VK call may be created vs reused. Network / TURN errors never
 * authorize a new call by themselves.
 */
enum class CallUpdateDecision {
    ReuseLive,
    RefreshCredentials,
    RestoreSockets,
    WaitForNetwork,
    WaitUserAction,
    CreateNewCall,
}

fun decideCallUpdate(
    validity: CallValidity,
    underlayAllowsOps: Boolean,
    userRequestedNew: Boolean,
    autoRecreate: Boolean,
    createInFlight: Boolean,
): CallUpdateDecision {
    if (createInFlight) return CallUpdateDecision.WaitUserAction
    if (userRequestedNew) return CallUpdateDecision.CreateNewCall
    if (!underlayAllowsOps) return CallUpdateDecision.WaitForNetwork
    return when (validity) {
        CallValidity.Valid -> CallUpdateDecision.ReuseLive
        CallValidity.UnknownDueToNetwork -> CallUpdateDecision.WaitForNetwork
        CallValidity.CredentialsExpired -> CallUpdateDecision.RefreshCredentials
        CallValidity.NeedsAuth -> CallUpdateDecision.WaitUserAction
        CallValidity.ConfirmedDead ->
            if (autoRecreate) CallUpdateDecision.CreateNewCall else CallUpdateDecision.WaitUserAction
    }
}

@Suppress("UNUSED_PARAMETER")
fun shouldCreateNewCall(
    ipChanged: Boolean,
    simChanged: Boolean,
    networkHandleChanged: Boolean,
    timeout: Boolean,
    turnQuota: Boolean,
    staleNonce: Boolean,
    allocationMismatch: Boolean,
    anonymTokenOutdated: Boolean,
    credentialsExpired: Boolean,
    callConfirmedDead: Boolean,
    userRequested: Boolean,
): Boolean = userRequested || callConfirmedDead
