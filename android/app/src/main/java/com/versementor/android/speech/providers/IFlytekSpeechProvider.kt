package com.versementor.android.speech.providers

import android.content.Context
import com.versementor.android.speech.SpeechProviderId

class IFlytekSpeechProvider(
    context: Context,
    callbacks: SpeechProviderCallbacks
) : DemoThirdPartySpeechProvider(
    context = context,
    descriptor = SpeechProviderDescriptor(
        id = SpeechProviderId.IFLYTEK,
        displayName = "iFlytek"
    ),
    callbacks = callbacks,
    demoScript = listOf(
        "静夜思",
        "唐李白",
        "床前明月光",
        "疑是地上霜",
        "举头望明月",
        "低头思故乡"
    )
)