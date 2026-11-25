package com.dominion.assistant

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
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
import com.dominion.assistant.data.AssistantProfile
import com.dominion.assistant.data.WorkItem
import com.dominion.assistant.data.RemotePc
import com.dominion.assistant.data.PcTurn
import com.dominion.assistant.data.InboxMessage
import com.dominion.assistant.data.WorkSection
import com.dominion.assistant.push.NativePush
import com.dominion.assistant.reminder.NativeReminders
import com.dominion.assistant.reminder.LocalReminder
import com.dominion.assistant.voice.VoicePairing
import com.dominion.assistant.voice.voiceId
import com.dominion.assistant.voice.VoiceCallState
import com.dominion.assistant.voice.VoiceCallService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = AccountVault(application)
    private val history = ConversationStore(application)
    private val repository = AssistantRepository()
    private val notifications = NativePush(application)
    private val localReminders = NativeReminders(application)
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
    var profile by mutableStateOf(AssistantProfile())
        private set
    var profileBusy by mutableStateOf(false)
        private set
    var reminders by mutableStateOf<List<LocalReminder>>(emptyList())
        private set
    var reminderBusy by mutableStateOf(false)
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedModel by mutableStateOf("")
        private set
    var jobs by mutableStateOf<List<WorkItem>>(emptyList())
        private set
    var pcs by mutableStateOf<List<RemotePc>>(emptyList())
        private set
    var voiceBusy by mutableStateOf(false)
        private set
    var pcTurns by mutableStateOf<List<PcTurn>>(emptyList())
        private set
    var selectedPcId by mutableStateOf("")
        private set
    var inbox by mutableStateOf<List<InboxMessage>>(emptyList())
        private set
    var pcBusy by mutableStateOf(false)
        private set
    var pushConfigured by mutableStateOf(notifications.configured())
        private set
    var pushEnabled by mutableStateOf(false)
        private set
    var pushBusy by mutableStateOf(false)
        private set
    var dynamicWorkUi by mutableStateOf<List<WorkSection>?>(null)
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
                        val restored = withContext(Dispatchers.IO) {
                            history.readProfile(previous.id) to history.history(previous.id)
                        }
                        profile = restored.first
                        messages = restored.second
                        account = previous
                        error = "Cloud 연결을 확인해 주세요."
                    }
                } catch (_: Exception) {
                    val restored = withContext(Dispatchers.IO) {
                            history.readProfile(previous.id) to history.history(previous.id)
                        }
                        profile = restored.first
                        messages = restored.second
                        account = previous
                    error = "네트워크 연결을 확인해 주세요."
                }
            }
        }
    }

    private suspend fun install(value: Account) {
        val saved = withContext(Dispatchers.IO) {
            val restoredProfile = history.readProfile(value.id)
            val restoredMessages = history.history(value.id)
            vault.save(value)
            restoredProfile to restoredMessages
        }
        profile = saved.first
        messages = saved.second
        account = value
        refreshPushStatus()
        error = ""
        refreshModels()
        refreshJobs()
        refreshPcs()
        refreshInbox()
        refreshWorkUi()
        refreshReminders()
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
        val hadCall=VoiceCallState.state.value.phase in setOf("connecting","connected","reconnecting")
        account = null
        messages = emptyList()
        profile = AssistantProfile()
        reminders = emptyList()
        pushEnabled = false
        jobs = emptyList()
        pcs = emptyList()
        pcTurns = emptyList()
        inbox = emptyList()
        selectedPcId = ""
        dynamicWorkUi = null
        models = emptyList()
        selectedModel = ""
        error = ""
        viewModelScope.launch {
            if(hadCall){
                VoiceCallService.command(getApplication(),VoiceCallService.STOP)
                withTimeoutOrNull(9500) {
                    VoiceCallState.state.first { it.phase=="idle" || it.phase=="error" }
                }
            }
            if (previous != null) {
                // Unsubscribe before revoking the app session.
                try { notifications.disable(previous) } catch (_: Exception) {}
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
                val response = repository.reply(current, selectedModel, messages, profile)
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

    fun saveProfile(next: AssistantProfile, onSaved: () -> Unit = {}) {
        val current = account ?: return
        if (profileBusy) return
        val valid = try { next.validated() } catch (e: Exception) {
            error = e.message ?: "프로필 설정을 확인해 주세요."
            return
        }
        profileBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { history.saveProfile(current.id, valid) }
                if (account?.id == current.id) {
                    profile = valid
                    onSaved()
                }
            } catch (e: Exception) {
                error = e.message ?: "프로필 저장에 실패했습니다."
            } finally { profileBusy = false }
        }
    }

    fun syncProfileToPc(pc: RemotePc) {
        val current = account ?: return
        if (profileBusy) return
        profileBusy = true
        viewModelScope.launch {
            try {
                repository.syncPcProfile(current, pc, profile)
                if (account?.id == current.id) {
                    error = "선택한 PC에 프로필을 반영했습니다."
                }
            } catch (e: Exception) {
                error = "PC 프로필 반영 실패: " + (e.message ?: "네트워크 오류")
            } finally { profileBusy = false }
        }
    }

    fun refreshReminders() {
        val current=account ?: return
        viewModelScope.launch {
            try {
                val list=localReminders.list(current.id)
                if(account?.id==current.id)reminders=list
            }catch(e:Exception){
                if(account?.id==current.id)
                    error=e.message?:"예약 알림을 확인하지 못했습니다."
            }
        }
    }

    fun scheduleReminder(title:String,dueAt:Long,onSaved:()->Unit={}) {
        val current=account ?: return
        if(reminderBusy)return
        reminderBusy=true
        viewModelScope.launch {
            try {
                localReminders.schedule(current.id,title,dueAt)
                if(account?.id==current.id){
                    onSaved()
                    refreshReminders()
                }
            }catch(e:Exception){
                if(account?.id==current.id)
                    error=e.message?:"예약 알림을 저장하지 못했습니다."
            }finally{reminderBusy=false}
        }
    }

    fun cancelReminder(id:String) {
        val current=account ?: return
        if(reminderBusy)return
        reminderBusy=true
        viewModelScope.launch {
            try {
                localReminders.cancel(current.id,id)
                if(account?.id==current.id)refreshReminders()
            }catch(e:Exception){
                if(account?.id==current.id)
                    error=e.message?:"예약 알림 취소에 실패했습니다."
            }finally{reminderBusy=false}
        }
    }

    fun refreshWorkUi() {
        val current=account?:return
        viewModelScope.launch {
            try {
                val result=repository.workUi(current)
                if(account?.id==current.id) dynamicWorkUi=result
            }catch(_:Exception) {
                // Static, locally compiled menus remain available when offline.
                if(account?.id==current.id)dynamicWorkUi=null
            }
        }
    }

    fun refreshPushStatus() {
        pushConfigured=notifications.configured()
        pushEnabled=account?.let{notifications.enabled(it)}?:false
    }
    fun enablePush() {
        val current=account?:return
        if(pushBusy)return
        pushBusy=true
        viewModelScope.launch {
            try {
                notifications.enable(current)
                if(account?.id==current.id)refreshPushStatus()
            }catch(e:Exception){
                error=e.message?:"알림 등록에 실패했습니다."
            }finally{pushBusy=false}
        }
    }
    fun disablePush() {
        val current=account?:return
        if(pushBusy)return
        pushBusy=true
        viewModelScope.launch {
            try {
                notifications.disable(current)
                if(account?.id==current.id)refreshPushStatus()
            }catch(e:Exception){
                error=e.message?:"알림 해제에 실패했습니다."
            }finally{pushBusy=false}
        }
    }

    fun refreshInbox() {
        val current=account ?: return
        viewModelScope.launch {
            try {
                val result=repository.inbox(current)
                if(account?.id==current.id) inbox=result
            }catch(e:Exception){
                error=e.message?:"PC 알림을 확인하지 못했습니다."
            }
        }
    }

    private var pcHistorySequence=0

    fun refreshPcWork(pc:RemotePc) {
        val current=account ?: return
        if(selectedPcId!=pc.id)pcTurns=emptyList()
        selectedPcId=pc.id
        val sequence=++pcHistorySequence
        viewModelScope.launch {
            try {
                val cached=withContext(Dispatchers.IO) {
                    history.pcHistory(current.id,pc.id)
                }
                if(account?.id==current.id&&selectedPcId==pc.id&&sequence==pcHistorySequence)
                    pcTurns=cached
                if(!pc.online)return@launch
                val latest=repository.pcHistory(current,pc)
                withContext(Dispatchers.IO) {
                    history.mirrorPcHistory(current.id,pc.id,latest)
                }
                val mirrored=withContext(Dispatchers.IO) {
                    history.pcHistory(current.id,pc.id)
                }
                if(account?.id==current.id&&selectedPcId==pc.id&&sequence==pcHistorySequence)
                    pcTurns=mirrored
            }catch(e:Exception){
                // Cache stays accessible when PC is disconnected.
                if(account?.id==current.id&&selectedPcId==pc.id&&sequence==pcHistorySequence)
                    error=e.message?:"PC 최신 상태 조회에 실패했습니다."
            }
        }
    }

    fun clearPcCache(pc:RemotePc) {
        val current=account ?:return
        val seq=++pcHistorySequence
        viewModelScope.launch {
            withContext(Dispatchers.IO) { history.clearPcMirror(current.id,pc.id) }
            if(account?.id==current.id&&selectedPcId==pc.id&&seq==pcHistorySequence)
                pcTurns=emptyList()
        }
    }

    fun sendPcWork(pc:RemotePc,instruction:String,onAccepted:()->Unit={}) {
        val current=account ?: return
        if(pcBusy||instruction.trim().isEmpty())return
        pcBusy=true
        viewModelScope.launch {
            try {
                repository.sendPc(current,pc,instruction)
                if(account?.id==current.id){
                    onAccepted()
                    refreshPcWork(pc)
                }
            }catch(e:Exception){
                error="PC 업무 접수 상태가 불확실합니다. PC 업무 기록을 먼저 확인해 주세요. "+
                    (e.message?:"연결 오류")
                if(account?.id==current.id)refreshPcWork(pc)
            }finally { pcBusy=false }
        }
    }

    fun sendPcAttachment(pc:RemotePc,uri:Uri,instruction:String,onAccepted:()->Unit={}) {
        val current=account?:return
        if(pcBusy||instruction.trim().isBlank())return
        pcBusy=true
        error=""
        viewModelScope.launch {
            try {
                val attachment=com.dominion.assistant.data.PcAttachmentPolicy.read(
                    getApplication(),uri)
                repository.sendPcAttachment(current,pc,instruction,attachment)
                if(account?.id==current.id) {
                    onAccepted()
                    refreshPcWork(pc)
                }
            } catch(e:IllegalArgumentException) {
                if(account?.id==current.id)error=e.message?:"첨부파일을 확인해 주세요."
            } catch(e:Exception) {
                if(account?.id==current.id) {
                    error="PC 첨부/작업 접수 상태가 불확실합니다. 먼저 PC 업무 기록을 확인해 주세요. "+
                        (e.message?:"연결 오류")
                    refreshPcWork(pc)
                }
            } finally {pcBusy=false}
        }
    }

    fun cancelPcWork(pc:RemotePc,requestId:String) {
        val current=account ?: return
        if(pcBusy)return
        pcBusy=true
        viewModelScope.launch {
            try{
                repository.cancelPc(current,pc,requestId)
                if(account?.id==current.id)refreshPcWork(pc)
            }catch(e:Exception){
                error=e.message?:"이미 시작한 작업은 PC에서 직접 확인해 주세요."
            }finally { pcBusy=false }
        }
    }

    fun refreshPcs() {
        val current=account?:return
        viewModelScope.launch {
            try {
                val found=repository.pcs(current)
                if(account?.id==current.id)pcs=found
            }catch(e:Exception){error=e.message?:"PC 연결 목록을 가져오지 못했습니다."}
        }
    }

    fun startVoice(pc:RemotePc, onReady:(VoicePairing,RemotePc,String)->Boolean) {
        val current=account?:return
        if(voiceBusy||!pc.online)return
        voiceBusy=true
        error=""
        viewModelScope.launch {
            val id=voiceId()
            try {
                val pairing=repository.startVoice(current,pc,id)
                val started=account?.id==current.id&&onReady(pairing,pc,id)
                if(!started){
                    try{repository.stopVoice(current,pc,id)}catch(_:Exception){}
                    if(account?.id==current.id)error="통화 화면에서 다시 시도해 주세요."
                }
            }catch(e:Exception){
                error=e.message?:"PC 음성 통화 시작 실패"
                // A timed-out request may have executed; never automatically start it again.
            }finally{voiceBusy=false}
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

    fun connectGoogle(open: (String) -> Unit) {
        val current = account ?: return
        viewModelScope.launch {
            try {
                open(repository.googleConnect(current))
            } catch (e: Exception) {
                error = e.message ?: "Google 서비스 연결 실패"
            }
        }
    }

    fun reviewWork(item: WorkItem, action: String, open: (String) -> Unit = {}) {
        val current = account ?: return
        viewModelScope.launch {
            try {
                val link = repository.browserAction(current, item, action)
                if (link != null) open(link)
                else refreshJobs()
            } catch (e: Exception) {
                error = e.message ?: "업무 승인 처리 실패"
            }
        }
    }

    fun uploadWorkFile(item: WorkItem, uri: Uri) {
        val current = account ?: return
        viewModelScope.launch {
            try {
                val (name, mime, bytes) = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val mime = resolver.getType(uri) ?: throw IllegalArgumentException("지원하지 않는 파일")
                    val suggested = when (mime) {
                        "application/pdf" -> "document.pdf"
                        "image/jpeg" -> "image.jpg"
                        "image/png" -> "image.png"
                        else -> throw IllegalArgumentException("PDF, JPG, PNG만 첨부할 수 있습니다.")
                    }
                    var name = suggested
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: suggested
                    }
                    val input = resolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("선택한 파일을 읽을 수 없습니다.")
                    val output = ByteArrayOutputStream()
                    input.use {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = it.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > 2 * 1024 * 1024) {
                                throw IllegalArgumentException("첨부는 최대 2MB입니다.")
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                    Triple(name, mime, output.toByteArray())
                }
                repository.browserUpload(current, item, name, mime, bytes)
                refreshJobs()
            } catch (e: Exception) {
                error = e.message ?: "파일을 전송하지 못했습니다."
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
