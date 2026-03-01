package com.versementor.android.speech.providers

import android.content.Context
import com.versementor.android.speech.SpeechProviderId

class VolcengineSpeechProvider(
    context: Context,
    callbacks: SpeechProviderCallbacks
) : DemoThirdPartySpeechProvider(
    context = context,
    descriptor = SpeechProviderDescriptor(
        id = SpeechProviderId.VOLCENGINE,
        displayName = "Volcengine"
    ),
    callbacks = callbacks,
    demoScript = listOf(
        "春晓",
        "唐孟浩然",
        "春眠不觉晓",
        "处处闻啼鸟",
        "夜来风雨声",
        "花落知多少"
    )
)