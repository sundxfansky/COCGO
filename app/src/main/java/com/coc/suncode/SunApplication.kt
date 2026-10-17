package com.coc.suncode

import android.app.Application
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.core.util.fileactions.LogHelper

class SunApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Services and model extraction can emit logs before MainActivity exists.
        ShowMessage.init(this)
        LogHelper.initTimber(this)
    }
}
