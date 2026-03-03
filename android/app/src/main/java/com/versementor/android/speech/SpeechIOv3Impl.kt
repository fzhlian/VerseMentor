package com.versementor.android.speech

import android.content.Context

/**
 * V3 speech I/O facade. Internally delegates to SpeechIO, which now runs ASR/TTS in two-provider mode.
 */
class SpeechIOv3Impl(context: Context) : ISpeechIO by SpeechIO(context)
