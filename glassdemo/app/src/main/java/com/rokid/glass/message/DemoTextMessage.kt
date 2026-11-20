package com.rokid.glass.message

import com.google.gson.Gson
import com.rokid.glass.bean.CustomMessage

/**
 * 把 demo 里的普通文本封装成 JSON 对象后再发给手机。
 *
 * 手机端（企业版 App）的 DeviceLinkerManager 会把收到的每一条经典蓝牙文本都按
 * CustomMessage 解析；直接发送裸文本会抛出 JsonSyntaxException，导致手机端主线程
 * 崩溃、蓝牙连接断开。这里统一包一层 JSON，保证手机端能正常解析并忽略未知 type。
 */
object DemoTextMessage {
    const val TYPE = "DEMO_TEXT"

    private val gson = Gson()

    fun encode(text: String): String = gson.toJson(CustomMessage().apply {
        type = TYPE
        message = text
    })
}
