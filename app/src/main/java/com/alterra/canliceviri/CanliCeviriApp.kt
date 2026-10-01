package com.alterra.canliceviri

import android.app.Application
import android.content.Context
import com.alterra.canliceviri.pipeline.ConversationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Motor uygulama ömründe yaşıyor: ekran kapanınca ya da etkinlik yeniden kurulunca çeviri sürsün diye
 * ön plan hizmeti ([TranslationService]) ve arayüz aynı motoru paylaşıyor.
 */
class CanliCeviriApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine: ConversationEngine by lazy { ConversationEngine(this, appScope) }

    companion object {
        fun of(context: Context): CanliCeviriApp = context.applicationContext as CanliCeviriApp
    }
}
