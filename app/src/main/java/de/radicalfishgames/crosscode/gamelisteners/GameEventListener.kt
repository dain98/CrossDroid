package de.radicalfishgames.crosscode.gamelisteners


abstract class GameEventListener(protected val eventManager: GameEventManager) {
    abstract val interfaceName: String

    abstract val requiredModuleName: String
    abstract val eventObjectName: String
    abstract val eventType: String

    abstract val onEventJS: String
}