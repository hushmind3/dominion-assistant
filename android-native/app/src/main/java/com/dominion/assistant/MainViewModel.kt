package com.dominion.assistant

import android.app.Application
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
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
import com.dominion.assistant.data.AssistantIntent
import com.dominion.assistant.data.AssistantTaskIntent
import com.dominion.assistant.device.NativeDeviceActions
import com.dominion.assistant.data.PreparedErrand
import com.dominion.assistant.data.BrowserDocument
import com.dominion.assistant.data.WorkItem
import com.dominion.assistant.data.RemotePc
import com.dominion.assistant.data.PcTurn
import com.dominion.assistant.data.InboxMessage
import com.dominion.assistant.data.WorkSection
import com.dominion.assistant.data.MailFilter
import com.dominion.assistant.data.MailStatus
import com.dominion.assistant.data.MailAlert
import com.dominion.assistant.data.SearchSource
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
    var mailStatus by mutableStateOf(MailStatus())
        private set
    var mailAlerts by mutableStateOf<List<MailAlert>>(emptyList())
        private set
    var mailAvailable by mutableStateOf(true)
        private set
    var mailBusy by mutableStateOf(false)
        private set
    var searchBusy by mutableStateOf(false)
        private set
    var searchResults by mutableStateOf<List<SearchSource>>(emptyList())
        private set
    var searchError by mutableStateOf("")
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedModel by mutableStateOf("")
        private set
    var jobs by mutableStateOf<List<WorkItem>>(emptyList())
        private set
    var pendingErrands by mutableStateOf<List<PreparedErrand>>(emptyList())
        private set
    var sendingErrandId by mutableStateOf<String?>(null)
        private set
    var sharingDocumentId by mutableStateOf<String?>(null)
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
                            Triple(history.readProfile(previous.id), history.history(previous.id),
                                history.errands(previous.id))
                        }
                        profile = restored.first
                        messages = restored.second
                        pendingErrands = restored.third
                        account = previous
                        error = "Cloud 연결을 확인해 주세요."
                    }
                } catch (_: Exception) {
                    val restored = withContext(Dispatchers.IO) {
                            Triple(history.readProfile(previous.id), history.history(previous.id),
                                history.errands(previous.id))
                        }
                        profile = restored.first
                        messages = restored.second
                        pendingErrands = restored.third
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
            Triple(restoredProfile, restoredMessages, history.errands(value.id))
        }
        profile = saved.first
        messages = saved.second
        pendingErrands = saved.third
        account = value
        refreshPushStatus()
        error = ""
        refreshModels()
        refreshJobs()
        refreshPcs()
        refreshInbox()
        refreshWorkUi()
        refreshReminders()
        refreshMail()
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
        pendingErrands = emptyList()
        sendingErrandId = null
        profile = AssistantProfile()
        reminders = emptyList()
        mailStatus = MailStatus()
        mailAlerts = emptyList()
        searchResults = emptyList()
        searchError = ""
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
                if (account?.id != current.id) return@launch
                messages = messages + ChatLine("user", input, System.currentTimeMillis())
                // A brief follow-up accepts the already prepared task; do not ask
                // the LLM to invent a second task or duplicate its job ID.
                val ready = pendingErrands.lastOrNull { it.state == "ready" }
                val continuation = input.matches(Regex(
                    "^(진행(해|해줘|해주세요)?|시작해줘|계속해|계속 진행해줘|해줘|부탁해)[.!?\\s]*$"
                ))
                if (ready != null && continuation) {
                    approveErrand(ready.id)
                    val note = "이전에 요청하신 심부름을 진행하고 있어요."
                    withContext(Dispatchers.IO) { history.append(current.id, "assistant", note) }
                    if (account?.id == current.id) {
                        messages = messages + ChatLine("assistant", note, System.currentTimeMillis())
                        onReply?.invoke(note)
                    }
                    return@launch
                }
                val response = repository.reply(current, selectedModel, messages, profile)
                if (account?.id != current.id) return@launch
                val proposal = AssistantTaskIntent.parse(response)
                val answer: String = when (proposal) {
                    is AssistantIntent.Browser, is AssistantIntent.Google,
                    is AssistantIntent.MailWatch -> {
                        val errand = PreparedErrand(
                            java.util.UUID.randomUUID().toString(),
                            proposal, System.currentTimeMillis()
                        )
                        withContext(Dispatchers.IO) { history.saveErrand(current.id, errand) }
                        if (account?.id != current.id) return@launch
                        pendingErrands = pendingErrands + errand
                        // A chat request is already the user's request to start the errand.
                        // Authentication, uploads and consequential submissions are gated
                        // by the server at the point where the action actually occurs.
                        approveErrand(errand.id)
                        "심부름 접수를 진행하고 있어요. 다른 대화를 계속하셔도 됩니다. 인증이나 최종 승인이 필요할 때 알려드릴게요."
                    }
                    is AssistantIntent.Search -> {
                        val sources = repository.searchWeb(current, proposal.query)
                        if (sources.isEmpty()) {
                            "검색 결과를 찾지 못했어요. 다른 검색어로 다시 부탁해 주세요."
                        } else buildString {
                            append("찾아본 자료예요.\n")
                            for (source in sources.take(5)) {
                                append("\n• ").append(source.title.take(100))
                                append("\n").append(source.url)
                                if (source.description.isNotBlank()) {
                                    append("\n").append(source.description.take(260))
                                }
                            }
                        }
                    }
                    is AssistantIntent.Ask -> proposal.question
                    is AssistantIntent.Device -> NativeDeviceActions.open(getApplication(), proposal)
                    null -> if (AssistantTaskIntent.looksLikeAction(response))
                        "이 심부름의 실행 정보를 확인할 수 없어요. 사이트 주소나 필요한 조건을 알려주세요."
                    else response
                }
                withContext(Dispatchers.IO) { history.append(current.id, "assistant", answer) }
                if (account?.id == current.id) {
                    messages = messages + ChatLine("assistant", answer, System.currentTimeMillis())
                    onReply?.invoke(answer)
                }
            } catch (e: Exception) {
                if (account?.id == current.id) {
                    error = "답변을 완료하지 못했습니다: " + (e.message ?: "네트워크 오류")
                }
            } finally {
                busy = false
            }
        }
    }

    /** A saved proposal is not an execution record. The user explicitly starts it. */
    fun approveErrand(id: String) {
        val current = account ?: return
        val draft = pendingErrands.firstOrNull { it.id == id && it.state == "ready" }
            ?: return
        if (sendingErrandId != null) return
        sendingErrandId = id
        viewModelScope.launch {
            try {
                when (val intent = draft.intent) {
                    is AssistantIntent.Browser ->
                        repository.browser(current, intent.url, intent.goal, draft.id)
                    is AssistantIntent.Google ->
                        repository.google(current, intent.operation, draft.id)
                    is AssistantIntent.MailWatch ->
                        repository.startMail(current,
                            MailFilter(intent.mode,intent.sender,intent.keyword))
                    else -> throw IllegalArgumentException("지원하지 않는 심부름입니다.")
                }
                if (draft.intent is AssistantIntent.MailWatch) {
                    withContext(Dispatchers.IO) { history.deleteErrand(current.id, draft.id) }
                } else {
                    withContext(Dispatchers.IO) {
                        history.updateErrandState(current.id, draft.id, "submitted")
                    }
                }
                if (account?.id == current.id) {
                    pendingErrands = if (draft.intent is AssistantIntent.MailWatch)
                        pendingErrands.filterNot { it.id == id }
                    else pendingErrands.map {
                        if(it.id == id) it.copy(state="submitted") else it
                    }
                    val note = if (draft.intent is AssistantIntent.MailWatch)
                        "메일 알림을 설정했어요." else
                        "심부름을 접수했어요. 진행 상태는 대화창에서 확인할 수 있어요."
                    withContext(Dispatchers.IO) { history.append(current.id, "assistant", note) }
                    messages = messages + ChatLine("assistant", note, System.currentTimeMillis())
                    if (draft.intent is AssistantIntent.MailWatch) refreshMail()
                    else refreshJobs()
                }
            } catch (e: Exception) {
                if (account?.id == current.id) {
                    val definitelyRejected = e is CloudError &&
                        e.statusCode in setOf(400, 401, 403, 404, 413)
                    if (!definitelyRejected) {
                        withContext(Dispatchers.IO) {
                            history.updateErrandState(current.id, id, "unknown")
                        }
                        pendingErrands = pendingErrands.map {
                            if (it.id == id) it.copy(state = "unknown") else it
                        }
                        error = "접수 여부를 확인할 수 없어요. 자동으로 중복 접수하지 않습니다."
                    } else {
                        error = "심부름 접수 실패: " + (e.message ?: "요청 거절")
                    }
                }
            } finally {
                if (sendingErrandId == id) sendingErrandId = null
            }
        }
    }

    /** Unknown submission outcomes are read-only checked; never silently replayed. */
    fun checkErrand(id: String) {
        val current = account ?: return
        val draft = pendingErrands.firstOrNull { it.id == id && it.state == "unknown" }
            ?: return
        if (sendingErrandId != null) return
        sendingErrandId = id
        viewModelScope.launch {
            try {
                val verified = if (draft.intent is AssistantIntent.MailWatch) {
                    val status = repository.mailStatus(current)
                    val filter = draft.intent
                    status.enabled && status.filters.mode == filter.mode &&
                        status.filters.sender == filter.sender &&
                        status.filters.keyword == filter.keyword
                } else {
                    val latest = repository.jobs(current)
                    if (account?.id == current.id) jobs = latest
                    latest.any { it.id == draft.id }
                }
                if (account?.id != current.id) return@launch
                if (verified) {
                    withContext(Dispatchers.IO) {
                        if (draft.intent is AssistantIntent.MailWatch)
                            history.deleteErrand(current.id, draft.id)
                        else history.updateErrandState(current.id, draft.id, "submitted")
                    }
                    pendingErrands = if (draft.intent is AssistantIntent.MailWatch)
                        pendingErrands.filterNot { it.id == id }
                    else pendingErrands.map {
                        if(it.id == id) it.copy(state="submitted") else it
                    }
                    refreshJobs()
                } else {
                    error = "아직 서버에서 접수 기록을 찾지 못했어요. 중복 제출을 막기 위해 다시 보내지 않았습니다."
                }
            } catch (e: Exception) {
                if (account?.id == current.id)
                    error = "접수 상태 조회 실패: " + (e.message ?: "연결 오류")
            } finally {
                if (sendingErrandId == id) sendingErrandId = null
            }
        }
    }

    fun dismissErrand(id: String) {
        val current = account ?: return
        if (sendingErrandId == id) return
        if (pendingErrands.none { it.id == id && it.state != "submitted" }) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { history.deleteErrand(current.id, id) }
                if (account?.id == current.id)
                    pendingErrands = pendingErrands.filterNot { it.id == id }
            } catch (e: Exception) {
                if (account?.id == current.id)
                    error = e.message ?: "심부름 초안을 닫지 못했습니다."
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

    fun refreshMail() {
        val current=account?:return
        viewModelScope.launch {
            try {
                val state=repository.mailStatus(current)
                val alerts=repository.mailAlerts(current)
                if(account?.id==current.id) {
                    // The SQLite unique key makes repeated polls and push refreshes harmless.
                    val notes=withContext(Dispatchers.IO) {
                        history.mirrorMailAlerts(current.id,alerts)
                    }
                    if(account?.id!=current.id)return@launch
                    mailStatus=state
                    mailAlerts=alerts
                    mailAvailable=true
                    if(notes.isNotEmpty())messages=messages+notes
                }
            }catch(e:CloudError) {
                if(account?.id==current.id && e.statusCode in setOf(404,503)) {
                    mailAvailable=false
                    mailStatus=MailStatus()
                } else if(account?.id==current.id) error=e.message?:"메일 연결 오류"
            }catch(e:Exception) {
                if(account?.id==current.id)error=e.message?:"메일 연결 오류"
            }
        }
    }

    fun startMail(filter:MailFilter) {
        val current=account?:return
        if(mailBusy)return
        mailBusy=true
        viewModelScope.launch {
            try{
                repository.startMail(current,filter)
                refreshMail()
            }catch(e:Exception){error=e.message?:"메일 감시를 시작하지 못했습니다."}
            finally{mailBusy=false}
        }
    }

    fun stopMail() {
        val current=account?:return
        if(mailBusy)return
        mailBusy=true
        viewModelScope.launch {
            try{repository.stopMail(current);refreshMail()}
            catch(e:Exception){error=e.message?:"메일 감시를 중지하지 못했습니다."}
            finally{mailBusy=false}
        }
    }

    fun checkMail() {
        val current=account?:return
        if(mailBusy)return
        mailBusy=true
        viewModelScope.launch {
            try{
                repository.checkMail(current)
                refreshMail()
            }catch(e:Exception){error=e.message?:"메일 감시 확인 실패"}
            finally{mailBusy=false}
        }
    }

    fun clearMailAlerts() {
        val current=account?:return
        if(mailBusy)return
        mailBusy=true
        viewModelScope.launch {
            try{repository.clearMailAlerts(current);refreshMail()}
            catch(e:Exception){error=e.message?:"메일 알림 기록 삭제 실패"}
            finally{mailBusy=false}
        }
    }

    fun searchWeb(query:String) {
        val current=account?:return
        if(searchBusy)return
        searchBusy=true
        searchError=""
        viewModelScope.launch {
            try{
                val sources=repository.searchWeb(current,query)
                if(account?.id==current.id)searchResults=sources
            }catch(e:Exception){
                if(account?.id==current.id){
                    searchResults=emptyList()
                    searchError=e.message?:"검색 요청에 실패했습니다."
                }
            }finally{searchBusy=false}
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
                if (account?.id == current.id) {
                    jobs = list
                    val results = list.filter {
                        it.status in setOf("completed", "failed", "cancelled", "needs_review")
                    }.associateBy { it.id }
                    for (draft in pendingErrands.filter { it.state == "submitted" }) {
                        val outcome = results[draft.id] ?: continue
                        val note = when (outcome.status) {
                            "completed" -> "✅ 심부름 완료: " + outcome.title
                            "failed" -> "⚠️ 심부름 실패: " + outcome.title
                            "cancelled" -> "심부름 취소: " + outcome.title
                            else -> "확인이 필요한 심부름: " + outcome.title
                        } + buildString {
                            if(outcome.detail.isNotBlank()) append("\n").append(outcome.detail.take(600))
                            if(outcome.evidence != null) append("\n확인된 화면: ")
                                .append(outcome.evidence.take(350))
                            if(outcome.resultUrl != null) append("\n출처: ")
                                .append(outcome.resultUrl)
                            if(outcome.documents.isNotEmpty()) append("\n📄 PDF는 대화창에서 받을 수 있어요.")
                        }
                        val recorded = withContext(Dispatchers.IO) {
                            history.completeErrand(current.id, draft.id, note)
                        }
                        if (account?.id != current.id) break
                        pendingErrands = pendingErrands.filterNot { it.id == draft.id }
                        if (recorded) {
                            messages = messages + ChatLine(
                                "assistant", note, System.currentTimeMillis()
                            )
                        }
                    }
                }
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

    fun shareWorkDocument(item: WorkItem, document: BrowserDocument, onReady: (Uri) -> Unit) {
        val current = account ?: return
        if (sharingDocumentId != null) return
        if (item.kind != "browser" || item.documents.none { it.id == document.id }) return
        sharingDocumentId = document.id
        viewModelScope.launch {
            try {
                val bytes = repository.browserDocument(current, item, document)
                if(account?.id != current.id)return@launch
                val uri = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val dir = File(app.cacheDir, "dominion-documents")
                    require(dir.isDirectory || dir.mkdirs()) { "PDF 저장 공간을 만들지 못했습니다." }
                    val old = System.currentTimeMillis() - 86400000
                    dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
                    val file = File(dir, java.util.UUID.randomUUID().toString() + ".pdf")
                    FileOutputStream(file).use { it.write(bytes) }
                    FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
                }
                if(account?.id == current.id)onReady(uri)
            } catch (e: Exception) {
                if(account?.id == current.id)error = "PDF 저장 실패: " +
                    (e.message ?: "파일을 확인하지 못했습니다.")
            } finally {
                if(sharingDocumentId == document.id)sharingDocumentId = null
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
            if(account?.id == current.id) {
                messages = emptyList()
                pendingErrands = emptyList()
            }
        }
    }

    override fun onCleared() {
        pairingJob?.cancel()
        history.close()
        super.onCleared()
    }
}
