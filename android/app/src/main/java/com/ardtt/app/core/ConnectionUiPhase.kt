package com.ardtt.app.core

enum class ConnectionUiPhase {
    Idle,
    Probing,
    Ready,
    Connecting,
    Connected,
    WaitingForNetwork,
    NetworkSuspended,
    InternetUnconfirmed,
    CaptivePortal,
    Recovering,
    SwitchingToWifi,
    ReturningToMobile,
    SuspectedRestriction,
    DirectDespiteRestriction,
    NeedsUserAction,
    PausedTrustedWifi,
    Disconnecting,
    Error,
}

enum class ConnectionUiAction {
    OpenNetworkSettings,
    CancelWait,
    Disconnect,
    RetryProbe,
    RetryNow,
    OpenCaptivePortal,
    OpenDiagnostics,
    SignIn,
    PassCaptcha,
    OpenProfile,
}

data class ConnectionUiModel(
    val phase: ConnectionUiPhase = ConnectionUiPhase.Idle,
    val message: String = "",
    val details: String? = null,
    val actions: List<ConnectionUiAction> = emptyList(),
    val connState: ConnState = ConnState.Idle,
    val retryInMs: Long? = null,
)

fun connectionUiModel(
    phase: RecoveryPhase,
    activePath: VpnPath?,
    restriction: RestrictionHint,
    transport: TransportLifecycle,
    retryInMs: Long?,
    userActionKind: UserActionKind? = null,
    trustedWifi: Boolean = false,
    underlayKind: UnderlayKind = UnderlayKind.Other,
    pathReadiness: PathReadiness = PathReadiness.None,
): ConnectionUiModel {
    if (trustedWifi) {
        return ConnectionUiModel(
            phase = ConnectionUiPhase.PausedTrustedWifi,
            message = "Пауза",
            details = "Доверенная сеть Wi‑Fi",
            actions = listOf(ConnectionUiAction.Disconnect),
            connState = ConnState.PausedTrustedWifi,
        )
    }
    val disconnect = listOf(ConnectionUiAction.Disconnect)
    return when (phase) {
        RecoveryPhase.Idle -> ConnectionUiModel(
            phase = ConnectionUiPhase.Idle,
            message = "Отключено",
            connState = ConnState.Idle,
        )
        RecoveryPhase.WaitingForNetwork -> ConnectionUiModel(
            phase = ConnectionUiPhase.WaitingForNetwork,
            message = "Нет подключения. Включите Wi‑Fi или мобильный интернет",
            actions = listOf(
                ConnectionUiAction.OpenNetworkSettings,
                ConnectionUiAction.CancelWait,
            ),
            connState = ConnState.WaitingForNetwork,
        )
        RecoveryPhase.NetworkSuspended -> ConnectionUiModel(
            phase = ConnectionUiPhase.NetworkSuspended,
            message = "Мобильный интернет временно недоступен. Ожидаем восстановления",
            actions = disconnect,
            connState = ConnState.WaitingForNetwork,
        )
        RecoveryPhase.CaptivePortal -> ConnectionUiModel(
            phase = ConnectionUiPhase.CaptivePortal,
            message = "Войдите в сеть Wi‑Fi",
            actions = listOf(ConnectionUiAction.OpenCaptivePortal, ConnectionUiAction.Disconnect),
            connState = ConnState.CaptivePortal,
        )
        RecoveryPhase.Probing -> ConnectionUiModel(
            phase = ConnectionUiPhase.InternetUnconfirmed,
            message = "Сеть подключена, ожидаем передачу данных",
            actions = listOf(ConnectionUiAction.RetryProbe, ConnectionUiAction.Disconnect),
            connState = ConnState.Connecting,
        )
        RecoveryPhase.ConnectingDirect, RecoveryPhase.ConnectingBypass -> {
            val switching = phase == RecoveryPhase.ConnectingDirect &&
                activePath == VpnPath.Bypass &&
                underlayKind.prefersDirectInAuto()
            val returning = phase == RecoveryPhase.ConnectingBypass && activePath == VpnPath.Direct
            when {
                switching -> ConnectionUiModel(
                    phase = ConnectionUiPhase.SwitchingToWifi,
                    message = "Подключаемся напрямую по Wi‑Fi",
                    actions = disconnect,
                    connState = ConnState.Connecting,
                )
                returning -> ConnectionUiModel(
                    phase = ConnectionUiPhase.ReturningToMobile,
                    message = "Восстанавливаем обход через существующий звонок",
                    actions = disconnect,
                    connState = ConnState.Connecting,
                )
                else -> ConnectionUiModel(
                    phase = ConnectionUiPhase.Connecting,
                    message = when {
                        phase == RecoveryPhase.ConnectingBypass &&
                            restriction == RestrictionHint.Confirmed ->
                            "Похоже на белый список оператора. Подключаемся через обход"
                        phase == RecoveryPhase.ConnectingBypass &&
                            restriction != RestrictionHint.Suspected &&
                            restriction != RestrictionHint.Confirmed ->
                            "Прямое подключение недоступно. Подключаемся через обход"
                        phase == RecoveryPhase.ConnectingBypass ->
                            "Подключение через обход…"
                        underlayKind == UnderlayKind.Cellular ->
                            "Подключаемся напрямую через мобильную сеть"
                        underlayKind.prefersDirectInAuto() ->
                            "Подключаемся напрямую через Wi‑Fi"
                        else -> "Подключение…"
                    },
                    actions = disconnect,
                    connState = ConnState.Connecting,
                )
            }
        }
        RecoveryPhase.SwitchingToWifi -> ConnectionUiModel(
            phase = ConnectionUiPhase.SwitchingToWifi,
            message = "Подключаемся напрямую по Wi‑Fi",
            actions = disconnect,
            connState = ConnState.Connecting,
        )
        RecoveryPhase.ReturningToMobile -> ConnectionUiModel(
            phase = ConnectionUiPhase.ReturningToMobile,
            message = "Восстанавливаем обход через существующий звонок",
            actions = disconnect,
            connState = ConnState.Connecting,
        )
        RecoveryPhase.Backoff -> ConnectionUiModel(
            phase = ConnectionUiPhase.Recovering,
            message = if (retryInMs != null && retryInMs > 0L) {
                val seconds = ((retryInMs + 999L) / 1000L).coerceAtLeast(1L)
                "Связь прервалась. Восстановим подключение автоматически. Следующая попытка через $seconds с"
            } else {
                "Связь прервалась. Восстановим подключение автоматически"
            },
            actions = listOf(ConnectionUiAction.RetryNow, ConnectionUiAction.Disconnect),
            connState = ConnState.Recovering,
            retryInMs = retryInMs,
        )
        RecoveryPhase.NeedsUserAction -> {
            val (message, action) = when (userActionKind) {
                UserActionKind.Captcha ->
                    "Требуется проверка капчи" to ConnectionUiAction.PassCaptcha
                UserActionKind.SignIn ->
                    "Войдите в ВКонтакте, чтобы продолжить" to ConnectionUiAction.SignIn
                UserActionKind.Profile ->
                    "Исправьте профиль подключения" to ConnectionUiAction.OpenProfile
                UserActionKind.CallDead ->
                    "Звонок закрыт. Создайте новый код звонка" to ConnectionUiAction.SignIn
                null -> "Требуется действие" to ConnectionUiAction.OpenProfile
            }
            ConnectionUiModel(
                phase = ConnectionUiPhase.NeedsUserAction,
                message = message,
                actions = listOf(action, ConnectionUiAction.Disconnect),
                connState = ConnState.NeedsUserAction,
            )
        }
        RecoveryPhase.Connected -> {
            val despite = restriction == RestrictionHint.Suspected ||
                restriction == RestrictionHint.Confirmed
            val connectedMessage = when {
                pathReadiness == PathReadiness.Unsupported ->
                    "Туннель поднят. Проверка данных недоступна на этом сервере"
                pathReadiness == PathReadiness.BackendRunning && activePath == VpnPath.Bypass ->
                    "Обход запущен. Ожидаем обмен данными…"
                pathReadiness == PathReadiness.ProtocolReady && activePath == VpnPath.Direct ->
                    "Прямое подключение установлено. Ожидаем обмен данными…"
                pathReadiness == PathReadiness.ProtocolReady && activePath == VpnPath.Bypass ->
                    "Обход готов. Ожидаем обмен данными…"
                despite && activePath == VpnPath.Direct ->
                    "Прямое подключение работает. Возможны ограничения мобильной сети"
                activePath == VpnPath.Direct -> "Прямое подключение"
                activePath == VpnPath.Bypass && !despite ->
                    "Прямое подключение недоступно. Подключаемся через обход"
                activePath == VpnPath.Bypass -> "Обход"
                else -> "Подключено"
            }
            val phaseUi = when {
                pathReadiness == PathReadiness.Unsupported ||
                    pathReadiness == PathReadiness.BackendRunning ||
                    pathReadiness == PathReadiness.ProtocolReady ->
                    ConnectionUiPhase.Connected
                despite && activePath == VpnPath.Direct ->
                    ConnectionUiPhase.DirectDespiteRestriction
                restriction == RestrictionHint.Suspected && activePath == VpnPath.Bypass ->
                    ConnectionUiPhase.SuspectedRestriction
                else -> ConnectionUiPhase.Connected
            }
            val actions = buildList {
                add(ConnectionUiAction.Disconnect)
                if (restriction == RestrictionHint.Suspected ||
                    restriction == RestrictionHint.Confirmed
                ) {
                    add(ConnectionUiAction.OpenDiagnostics)
                }
            }
            ConnectionUiModel(
                phase = phaseUi,
                message = connectedMessage,
                details = when {
                    pathReadiness == PathReadiness.Unsupported ->
                        "Опциональная проверка данных не поддерживается"
                    pathReadiness != PathReadiness.PathConfirmed &&
                        pathReadiness != PathReadiness.None ->
                        "Маршрут ещё не подтверждён полезным обменом"
                    despite && activePath == VpnPath.Direct ->
                        "Возможны ограничения мобильной сети"
                    restriction == RestrictionHint.Suspected ->
                        "Похоже на ограничения мобильной сети"
                    else -> null
                },
                actions = actions,
                connState = ConnState.Connected,
            )
        }
    }.let { model ->
        if (transport == TransportLifecycle.Starting &&
            model.connState == ConnState.Connected
        ) {
            model.copy(
                phase = ConnectionUiPhase.Connecting,
                message = "Подключение…",
                connState = ConnState.Connecting,
                actions = listOf(ConnectionUiAction.Disconnect),
            )
        } else {
            model
        }
    }
}

enum class UserActionKind {
    Captcha,
    SignIn,
    Profile,
    CallDead,
}
