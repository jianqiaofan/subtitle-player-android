package com.jianqiaofan.subtitleplayer.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jianqiaofan.subtitleplayer.data.AppUpdates
import com.jianqiaofan.subtitleplayer.data.PreparedApk
import com.jianqiaofan.subtitleplayer.data.UpdateCheck
import com.jianqiaofan.subtitleplayer.domain.update.AppRelease
import com.jianqiaofan.subtitleplayer.domain.update.MSG_UP_TO_DATE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstallRequest(val id: Long, val path: String)

data class AppUpdateUiState(
    val busy: Boolean = false,
    val status: String = "",
    val message: String? = null,
    val offer: AppRelease? = null,
    val installRequest: InstallRequest? = null,
)

class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val updates = AppUpdates(app)
    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()
    private var requestId = 0L

    fun check() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "正在检查更新", offer = null) }
            val result = withContext(Dispatchers.IO) { updates.inspect() }
            when (result) {
                UpdateCheck.Latest -> _state.update { it.copy(busy = false, status = "", message = MSG_UP_TO_DATE) }
                is UpdateCheck.Available -> _state.update { it.copy(busy = false, status = "", offer = result.release) }
                is UpdateCheck.Failed -> _state.update { it.copy(busy = false, status = "", message = result.message) }
            }
        }
    }

    fun dismissOffer() {
        if (_state.value.busy) return
        _state.update { it.copy(offer = null) }
    }

    fun confirmInstall() {
        val offer = _state.value.offer ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "正在下载", offer = null) }
            val prepared = withContext(Dispatchers.IO) { updates.prepareInstall(offer) }
            when (prepared) {
                is PreparedApk.Ready -> {
                    requestId += 1
                    _state.update {
                        it.copy(
                            busy = false,
                            status = "",
                            installRequest = InstallRequest(requestId, prepared.file.absolutePath),
                        )
                    }
                }
                is PreparedApk.Failed -> _state.update { it.copy(busy = false, status = "", message = prepared.message) }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeInstall(message: String?) {
        _state.update { it.copy(installRequest = null, message = message) }
    }
}
