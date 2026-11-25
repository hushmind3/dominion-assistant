package com.dominion.assistant.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.dominion.assistant.data.AssistantIntent

/**
 * Explicit, OS-owned handoffs. This does not call, send, pay, submit or
 * manipulate third-party app UIs. The user finishes those actions in Android.
 */
object NativeDeviceActions {
    fun open(context: Context, request: AssistantIntent.Device): String {
        val op=request.operation
        val value=request.target
        val intent=when(op) {
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))
            "sms" -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", value, null))
            "contacts" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            "calendar" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "maps" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="+Uri.encode(value)))
            "open_url" -> Intent(Intent.ACTION_VIEW, Uri.parse(value))
            "share_text" -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, value),
                "내용 공유"
            )
            else -> return "이 휴대폰 동작은 지원하지 않습니다."
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            when(op) {
                "dial" -> "전화번호 입력 화면을 열었습니다. 발신은 직접 확인해 주세요."
                "sms" -> "문자 작성 화면을 열었습니다. 전송은 직접 확인해 주세요."
                "open_url" -> "요청하신 웹페이지를 열었습니다."
                "share_text" -> "공유할 앱을 선택하는 화면을 열었습니다."
                else -> "휴대폰의 해당 화면을 열었습니다."
            }
        } catch (_: ActivityNotFoundException) {
            "이 동작을 처리할 휴대폰 앱을 찾지 못했습니다."
        } catch (_: SecurityException) {
            "휴대폰에서 이 기능에 필요한 권한을 허용하지 않았습니다."
        }
    }
}
