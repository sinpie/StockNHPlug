package com.sinpie.stocknhplug.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sinpie.stocknhplug.application.HistoryExport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExportStatus(val busy: Boolean = false, val message: String = "")

/** Activity 재생성/기기 재인증 동안 메모리의 원본 스냅샷만 유지. 프로세스 사망 후 임의 재생성하지 않는다. */
class HistoryExportModel
@JvmOverloads
constructor(
    application: Application,
    // 단일 문서 스트림 경계. 테스트에서는 실제 사용자 파일 대신 메모리 스트림을 주입한다.
    private val openDocument: (Uri) -> java.io.OutputStream? = {
        application.contentResolver.openOutputStream(it, "wt")
    },
) : AndroidViewModel(application) {
    private var pending: HistoryExport? = null
    private val mutable = MutableStateFlow(ExportStatus())
    val state = mutable.asStateFlow()

    fun prepare(request: HistoryExport): Boolean {
        if (mutable.value.busy) return false
        pending = request
        mutable.value = ExportStatus(true, "저장 위치를 선택하세요.")
        return true
    }

    fun launchFailed() {
        pending = null
        mutable.value = ExportStatus(message = "저장 화면을 열 수 없습니다. 다시 시도하세요.")
    }

    /** 승인된 단일 content URI에만 쓴다. 경로/문서 권한을 영구 저장하거나 키를 내보내지 않는다. */
    fun save(uri: Uri?) {
        val request = pending
        pending = null
        if (uri == null) {
            mutable.value = ExportStatus(message = "저장을 취소했습니다.")
            return
        }
        if (request == null || uri.scheme != "content") {
            mutable.value = ExportStatus(message = "내보내기 정보가 만료되었습니다. 빈 파일을 삭제하고 다시 저장하세요.")
            return
        }
        mutable.value = ExportStatus(true, "통계 파일 저장 중…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    requireNotNull(openDocument(uri)).use { stream ->
                        request.write(stream) { context.ensureActive() }
                    }
                }
                mutable.value = ExportStatus(message = "통계 파일을 저장했습니다.")
            } catch (e: CancellationException) {
                mutable.value = ExportStatus(message = "저장이 중단되었습니다. 저장소의 불완전한 파일을 삭제하세요.")
                throw e
            } catch (_: Exception) {
                // 외부 제공자의 예외에는 URI/계좌 정보가 있을 수 있어 그대로 노출하지 않는다.
                mutable.value =
                    ExportStatus(message = "저장하지 못했습니다. 공간·접근 권한을 확인하고 불완전한 파일을 삭제한 뒤 다시 시도하세요.")
            }
        }
    }
}
