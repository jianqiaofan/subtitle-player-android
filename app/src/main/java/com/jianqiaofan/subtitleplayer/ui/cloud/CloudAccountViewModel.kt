package com.jianqiaofan.subtitleplayer.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jianqiaofan.subtitleplayer.data.CloudException
import com.jianqiaofan.subtitleplayer.data.CloudRepository
import com.jianqiaofan.subtitleplayer.domain.cloud.DEFAULT_CLOUD_SERVER
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudAccountUiState(
    val server: String = DEFAULT_CLOUD_SERVER,
    val username: String = "",
    val password: String = "",
    val loggedInAs: String = "",
    val busy: Boolean = false,
    val registerBlocked: Boolean = false,
    val message: String? = null,
)

class CloudAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CloudRepository(application)
    private val _state = MutableStateFlow(CloudAccountUiState())
    val state: StateFlow<CloudAccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val account = repository.account()
            _state.update {
                it.copy(
                    server = account.server,
                    username = account.username,
                    password = account.password,
                    loggedInAs = if (account.token.isNotBlank()) account.tokenUsername else "",
                )
            }
        }
    }

    fun updateServer(value: String) = edit { it.copy(server = value, registerBlocked = false) }

    fun updateUsername(value: String) = edit { it.copy(username = value, registerBlocked = false) }

    fun updatePassword(value: String) = edit { it.copy(password = value, registerBlocked = false) }

    fun register() = submit(register = true)

    fun login() = submit(register = false)

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun remindAccount() {
        _state.update { it.copy(message = "请先注册或登录。手机和电脑用同一个用户名，就是同一个用户。") }
    }

    private fun edit(transform: (CloudAccountUiState) -> CloudAccountUiState) {
        _state.update(transform)
    }

    private fun submit(register: Boolean) {
        val snapshot = _state.value
        if (snapshot.busy) return
        if (register && snapshot.registerBlocked) return
        if (snapshot.username.isBlank()) {
            _state.update { it.copy(message = "请先填写用户名。手机和电脑用同一个用户名，就是同一个用户。") }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (register) {
                    repository.register(snapshot.server, snapshot.username, snapshot.password)
                } else {
                    repository.login(snapshot.server, snapshot.username, snapshot.password)
                }
                val account = repository.account()
                _state.update {
                    it.copy(
                        busy = false,
                        username = account.username,
                        loggedInAs = account.tokenUsername,
                        message = if (register) "注册成功，已登录。" else "已登录。",
                    )
                }
            } catch (e: CloudException) {
                val blocked = register && e.status == 409
                _state.update {
                    it.copy(
                        busy = false,
                        registerBlocked = blocked,
                        message = e.message ?: "请求失败",
                    )
                }
            }
        }
    }
}
