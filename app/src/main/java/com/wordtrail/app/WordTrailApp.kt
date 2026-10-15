package com.wordtrail.app

import android.app.Application
import com.wordtrail.app.data.*

class WordTrailApp : Application() {
    val repository by lazy { Repository(this, WordDatabase.open(this)) }
    val settings by lazy { SettingsStore(this) }
}
