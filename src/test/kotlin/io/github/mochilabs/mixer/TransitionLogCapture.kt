package io.github.mochilabs.mixer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

internal class TransitionLogCapture(private val guildId: Long, debug: Boolean = false) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GuildMixer::class.java) as Logger
    private val previousLevel = logger.level
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
        logger.level = if (debug) Level.DEBUG else Level.INFO
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    fun infoLines(): List<String> = messages.filter { it.first == Level.INFO && it.second.startsWith("crossfade") }.map { it.second }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}
