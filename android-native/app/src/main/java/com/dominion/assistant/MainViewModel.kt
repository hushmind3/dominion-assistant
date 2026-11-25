package com.dominion.assistant

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dominion.assistant.data.Account
import com.dominion.assistant.data.AccountVault
import com.dominion.assistant.data.AssistantRepository
import com.dominion.assistant.data.ChatLine
import com.dominion.assistant.data.CloudError
import com.dominion.assistant.data.ConversationStore
import com.dominion.assistant.data.WorkItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = AccountVault(application)
    private val history = ConversationStore(application)
    private val repository = AssistantRepository()
    private var pairingJob: Job? = null

    var account by mutableStateOf<Account?>(null)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var loadingJobs by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var messages by mutableStateOf<List<ChatLine>>(emptyList())
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedModel by mutableStateOf("")
        private set
    var jobs by mutableStateOf<List<WorkItem>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            val previous = withContext(Dispatchers.IO) { vault.load() }
            if (previous != null) {
                try {
                    repository.verify(previous)
                    install(previous)
                } catch (e: CloudError) {
                    if (e.statusCode == 401 || e.statusCode == 403) {
                        withContext(Dispatchers.IO) { vault.clear() }
                        error = "로그인이 만료되었습니다. 다시 연결해 주세요."
                    } else {
                        // Keep the valid encrypted token and local journal during network outages.
                        account = previous
                        messages = withContext(Dispatchers.IO) { history.history(previous.id) }
                        error = "Cloud 연결을 확인해 주세요."
                    }
                } catch (_: Exception) {
                    account = previous
                    messages = withContext(Dispatchers.IO) { history.history(previous.id) }
                    error = "네트워크 연결을 확인해 주세요."
                }
            }
        }
    }

    private suspend fun install(value: Account) {
        withContext(Dispatchers.IO) { vault.save(value) }
        account = value
        error = ""
        messages = withContext(Dispatchers.IO) { history.history(value.id) }
        refreshModels()
        refreshJobs()
    }

    fun clearError() { error = "" }

    fun signIn(openVerification: (String) -> Unit) {
        if (signingIn || account != null) return
        signingIn = true
        error = ""
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            try {
                val pending = repository.beginPairing()
                openVerification(pending.url)
                install(repository.pollPairing(pending))
            } catch (_: CancellationException) {
                // User explicitly cancelled pending pairing.
            } catch (e: Exception) {
                error = e.message ?: "로그인에 실패했습니다."
            } finally {
                signingIn = false
                pairingJob = null
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        signingIn = false
    }

    fun signOut() {
        cancelPairing()
        val previous = account
        account = null
        messages = emptyList()
        jobs = emptyList()
        models = emptyList()
        selectedModel = ""
        error = ""
        viewModelScope.launch {
            if (previous != null) {
                try { repository.logout(previous) } catch (_: Exception) {}
                withContext(Dispatchers.IO) { vault.clear() }
            }
        }
    }

    fun refreshModels() {
        val current = account ?: return
        viewModelScope.launch {
            try {
                models = repository.models(current)
                if (selectedModel !in models) selectedModel = models.firstOrNull().orEmpty()
            } catch (e: Exception) {
                error = e.message ?: "모델 목록 조회 실패"
            }
        }
    }

    fun chooseModel(value: String) {
        if (value in models) selectedModel = value
    }

    fun send(text: String, onReply: ((String) -> Unit)? = null) {
        val current = account ?: return
        val input = text.trim().take(8000)
        if (input.isEmpty() || busy) return
        if (selectedModel.isBlank()) {
            error = "사용 가능한 모델을 먼저 선택해 주세요."
            refreshModels()
            return
        }
        busy = true
        error = ""
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { history.append(current.id, "user", input) }
                messages = messages + ChatLine("user", input, System.currentTimeMillis())
                val response = repository.reply(current, selectedModel, messages)
                withContext(Dispatchers.IO) { history.append(current.id, "assistant", response) }
                if (account?.id == current.id) {
                    messages = messages + ChatLine("assistant", response, System.currentTimeMillis())
                    onReply?.invoke(response)
                }
            } catch (e: Exception) {
                error = "답변을 완료하지 못했습니다: " + (e.message ?: "네트워크 오류")
            } finally {
                busy = false
            }
        }
    }

    fun refreshJobs() {
        val current = account ?: return
        if (loadingJobs) return
        loadingJobs = true
        viewModelScope.launch {
            try {
                val list = repository.jobs(current)
                if (account?.id == current.id) jobs = list
            } catch (e: Exception) {
                error = e.message ?: "업무 목록 조회 실패"
            } finally {
                loadingJobs = false
            }
        }
    }

    fun startGoogle(operation: String) {
        val current = account ?: return
        viewModelScope.launch {
            try {
                repository.google(current, operation)
                refreshJobs()
            } catch (e: Exception) {
                error = e.message ?: "Google 업무 실행 실패"
            }
        }
    }

    fun startBrowser(url: String, goal: String) {
        val current = account ?: return
        viewModelScope.launch {
            try {
                repository.browser(current, url, goal)
                refreshJobs()
            } catch (e: Exception) {
                error = e.message ?: "브라우저 업무 실행 실패"
            }
        }
    }

    fun cancelWork(item: WorkItem) {
        val current = account ?: return
        viewModelScope.launch {
            try {
                repository.cancel(current, item)
                refreshJobs()
            } catch (e: Exception) {
                error = e.message ?: "업무 취소 실패"
            }
        }
    }

    fun clearHistory() {
        val current = account ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { history.clear(current.id) }
            messages = emptyList()
        }
    }

    override fun onCleared() {
        pairingJob?.cancel()
        history.close()
        super.onCleared()
    }
}
