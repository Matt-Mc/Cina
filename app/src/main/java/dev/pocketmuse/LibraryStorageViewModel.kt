package dev.pocketmuse

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LibraryStorageViewModel(application: Application): AndroidViewModel(application) {
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()
    private val _message=MutableStateFlow("")
    val message=_message.asStateFlow()
    private val _size=MutableStateFlow<LibrarySize?>(null)
    val size=_size.asStateFlow()
    private val _restore=MutableStateFlow<RestorePreview?>(null)
    val restore=_restore.asStateFlow()
    private val _retention=MutableStateFlow<RetentionPreview?>(null)
    val retention=_retention.asStateFlow()
    private var encrypted: ByteArray?=null
    private val _ready=MutableStateFlow(false)
    val ready=_ready.asStateFlow()
    private fun work(block: suspend () -> Unit) {
        if(_busy.value) return
        _busy.value=true;_message.value=""
        viewModelScope.launch {
            try { block();_size.value=withContext(Dispatchers.IO) { LocalStore(getApplication()).use { LibraryStorage(it).size() } } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { _message.value=when(e) {
                is IllegalArgumentException -> e.message ?: "The backup could not be validated."
                else -> "The operation could not finish. Check the file and available storage, then try again."
            } }
            finally { _busy.value=false }
        }
    }
    fun refresh() = work { }
    fun prepare(password: CharArray) {
        if(_busy.value) { password.fill('\u0000');return }
        work {
            try {
                cancelBackup()
                encrypted=withContext(Dispatchers.IO) {
                    LocalStore(getApplication()).use { store ->
                        val repo=LibraryStorage(store);val payload=repo.export()
                        try {
                            repo.previewRestore(payload).clear()
                            LibraryBackupCrypto.encrypt(payload,password)
                        } finally { payload.fill(0) }
                    }
                }
                _ready.value=true
            } finally { password.fill('\u0000') }
        }
    }
    fun save(uri: Uri) = work {
        try {
            withContext(Dispatchers.IO) {
                val bytes=encrypted ?: error("Prepare the backup again.")
                getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes);it.flush() }
                    ?: error("Cannot open destination.")
            }
            _message.value="Encrypted backup saved. Keep your passphrase separately; Cina cannot recover it."
        } finally { cancelBackup() }
    }
    fun inspect(uri: Uri,password: CharArray) {
        if(_busy.value) { password.fill('\u0000');return }
        work {
            try {
                cancelRestore()
                _restore.value=withContext(Dispatchers.IO) {
                    val payload=getApplication<Application>().contentResolver.openInputStream(uri)?.use { LibraryBackupCrypto.decrypt(it,password) }
                        ?: error("Cannot open backup.")
                    try { LocalStore(getApplication()).use { LibraryStorage(it).previewRestore(payload) } }
                    finally { payload.fill(0) }
                }
            } finally { password.fill('\u0000') }
        }
    }
    fun applyRestore() = work {
        try {
            val preview=_restore.value ?: error("Open the backup again.")
            withContext(Dispatchers.IO) { LocalStore(getApplication()).use { LibraryStorage(it).restore(preview) } }
            _message.value="Library restored and search rebuilt. Chats, models and settings were kept."
        } finally { cancelRestore() }
    }
    fun previewCleanup(days: Int) = work {
        _retention.value=null
        _retention.value=withContext(Dispatchers.IO) { LocalStore(getApplication()).use { LibraryStorage(it).previewRetention(days) } }
    }
    fun cleanup() = work {
        try {
            val preview=_retention.value ?: error("Preview cleanup again.")
            withContext(Dispatchers.IO) { LocalStore(getApplication()).use { LibraryStorage(it).deleteRetained(preview) } }
            _message.value="Selected sources and their linked knowledge were removed. Existing backup files are unchanged."
        } finally { _retention.value=null }
    }
    fun cancelRestore() { _restore.value?.clear();_restore.value=null }
    fun cancelCleanup() { _retention.value=null }
    fun cancelBackup() { encrypted?.fill(0);encrypted=null;_ready.value=false }
    fun leave() { if(!_busy.value) { cancelRestore();cancelCleanup();cancelBackup() } }
    override fun onCleared() { cancelRestore();cancelBackup();super.onCleared() }
}
