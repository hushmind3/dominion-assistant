package com.dominion.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.dominion.assistant.data.AssistantProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Compress an explicitly selected image for private, account-local SQLite storage. */
object ProfilePhoto {
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val input=context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("사진을 읽을 수 없습니다.")
        val buffer=ByteArrayOutputStream()
        input.use {
            val chunk=ByteArray(8192)
            while(true) {
                val count=it.read(chunk)
                if(count<0)break
                if(buffer.size()+count>12*1024*1024)
                    throw IllegalArgumentException("비서 사진은 12MB 이하만 지원합니다.")
                buffer.write(chunk,0,count)
            }
        }
        val bytes=buffer.toByteArray()
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000) {
            "사진 파일이 올바르지 않습니다."
        }
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>384)sample*=2
        val options=BitmapFactory.Options().apply{inSampleSize=sample}
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            ?:throw IllegalArgumentException("사진을 변환할 수 없습니다.")
        try {
            var result=ByteArray(0)
            for(quality in listOf(90,80,65,50,35)) {
                val out=ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out)
                result=out.toByteArray()
                if(result.size<=170000)break
            }
            require(result.size in 1..170000){"비서 사진을 작게 줄일 수 없습니다."}
            "data:image/jpeg;base64,"+Base64.encodeToString(result,Base64.NO_WRAP)
        }finally{bitmap.recycle()}
    }
}

@Composable
fun AssistantAvatarView(profile:AssistantProfile,size:Dp,modifier:Modifier=Modifier) {
    val bitmap=remember(profile.photo) {
        runCatching {
            val data=profile.photo?.substringAfter("data:image/jpeg;base64,")
                ?.takeIf{it.isNotBlank()}?:return@runCatching null
            val bytes=Base64.decode(data,Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF304D3A)),
        contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap,contentDescription=profile.name+" 사진",
            contentScale=ContentScale.Crop,modifier=Modifier.size(size))
        else Text(avatarGlyph(profile.avatar),color=Color(0xFFB5E4BE),fontSize=(size.value*.53f).sp)
    }
}
