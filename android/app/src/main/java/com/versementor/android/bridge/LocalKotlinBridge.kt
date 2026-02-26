package com.versementor.android.bridge

import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionReducer
import com.versementor.android.session.SessionState

class LocalKotlinBridge(
    private val reducer: SessionReducer = SessionReducer()
) : SessionBridge {
    override fun reduce(state: SessionState, event: SessionEvent): SessionOutput {
        return reducer.reduce(state, event)
    }
}
