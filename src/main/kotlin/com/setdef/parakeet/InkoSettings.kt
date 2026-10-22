package com.setdef.parakeet

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "ParakeetSettings", storages = [Storage("parakeet.xml")])
class InkoSettings : SimplePersistentStateComponent<InkoSettings.State>(State()) {
    class State : BaseState() {
        /** An explicit path to the `inko` executable; null means auto-detect. */
        var executablePath by string()
    }

    companion object {
        fun getInstance(): InkoSettings = service()
    }
}
