package com.castpigeon.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionStateMachine(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _state = MutableStateFlow(ConnectionState.Idle)
    private val _role = MutableStateFlow(DeviceRole.Sender)
    private val _workMode = MutableStateFlow(WorkMode.Idle)
    private val _pairingDeviceName = MutableStateFlow<String?>(null)
    private val _connectedDeviceName = MutableStateFlow<String?>(null)

    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val role: StateFlow<DeviceRole> = _role.asStateFlow()
    val workMode: StateFlow<WorkMode> = _workMode.asStateFlow()
    val pairingDeviceName: StateFlow<String?> = _pairingDeviceName.asStateFlow()
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var timeoutJob: Job? = null

    fun setRole(newRole: DeviceRole) {
        if (_workMode.value == WorkMode.Idle) {
            _role.value = newRole
        }
    }

    fun setWorkMode(newMode: WorkMode) {
        _workMode.value = newMode
        if (newMode == WorkMode.Idle) {
            transitionTo(ConnectionState.Idle)
        }
    }

    fun transitionTo(newState: ConnectionState, deviceName: String? = null) {
        _state.value = newState
        _pairingDeviceName.value = deviceName.takeIf { newState == ConnectionState.PairingRequest }
        _connectedDeviceName.value = when (newState) {
            ConnectionState.Transferring -> deviceName
            ConnectionState.Idle,
            ConnectionState.Disconnecting -> null
            else -> _connectedDeviceName.value
        }

        timeoutJob?.cancel()
        if (newState == ConnectionState.Disconnecting) {
            timeoutJob = scope.launch {
                delay(500)
                transitionTo(ConnectionState.Idle)
            }
        }
    }

    fun scheduleIdleDisconnect(delayMillis: Long = 10_000L) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(delayMillis)
            if (_state.value == ConnectionState.Transferring) {
                transitionTo(ConnectionState.Disconnecting)
            }
        }
    }
}
