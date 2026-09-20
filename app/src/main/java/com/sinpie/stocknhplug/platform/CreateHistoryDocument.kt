package com.sinpie.stocknhplug.platform

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** CSV/ZIP를 실제 스트림으로 열 수 있는 문서 생성만 요청한다. 디렉터리/영구 권한은 요구하지 않는다. */
class CreateHistoryDocument(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}
