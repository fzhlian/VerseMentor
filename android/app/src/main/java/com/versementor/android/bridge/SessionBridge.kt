package com.versementor.android.bridge

import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionState

interface SessionBridge {
    fun reduce(state: SessionState, event: SessionEvent): SessionOutput
}
