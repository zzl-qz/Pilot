package com.pilot.mobile

import android.app.Application
import computer.iroh.IrohAndroid

class PilotApplication : Application() {
    var transportFailure: String? = null; private set
    override fun onCreate() {
        super.onCreate()
        try { IrohAndroid.installAndroidContext(this) }
        catch (_: LinkageError) { transportFailure = "当前设备架构不支持连接组件，请安装匹配的 APK。" }
    }
}
