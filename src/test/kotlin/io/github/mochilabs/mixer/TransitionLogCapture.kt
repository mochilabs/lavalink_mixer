package io.github.mochilabs.mixer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

internal class TransitionLogCapture(private val guildId: Long, debug: Boolean = false) : AutoCloseable {
    // GuildMixer's transition logs are split across the classes extracted from it.
    private val loggers = listOf(
        GuildMixer::class.java,
        CrossfadeEngine::class.java,
        OverlayController::class.java,
        SubDeck::class.java,
        FeederElection::class.java,
        AnalysisCoordinator::class.java,
    ).map { LoggerFactory.getLogger(it) as Logger }
    private val previousLevels = loggers.map { it.level }
    val messages = CopyOnWriteArrayList<Pair<Level, String>>()
    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            val message = event.formattedMessage
            if (message.contains("guild=$guildId") || message.contains("guild $guildId")) {
                messages += event.level to message
            }
        }
    }

    init {
        appender.context = loggers.first().loggerContext
        appender.start()
        for (logger in loggers) {
            logger.level = if (debug) Level.DEBUG else Level.INFO
            logger.addAppender(appender)
        }
    }

    fun infoLines(): List<String> = messages.filter { it.first == Level.INFO && it.second.startsWith("crossfade") }.map { it.second }

    override fun close() {
        for (logger in loggers) logger.detachAppender(appender)
        appender.stop()
        loggers.forEachIndexed { i, logger -> logger.level = previousLevels[i] }
    }
}
