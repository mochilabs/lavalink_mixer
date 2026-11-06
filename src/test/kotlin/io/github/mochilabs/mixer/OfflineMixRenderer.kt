package io.github.mochilabs.mixer

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.IPlayer
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object OfflineMixRenderer {
    private const val SAMPLE_RATE = 48_000
    private val FORMAT = StandardAudioDataFormats.DISCORD_PCM_S16_LE

    fun writeClickWav(
        file: File,
        bpm: Float,
        seconds: Int,
        channel: Int = 0,
        phaseMs: Long = 0L,
        sampleRate: Int = SAMPLE_RATE,
    ) {
        val count = seconds * sampleRate
        val period = sampleRate * 60.0 / bpm
        val phase = phaseMs * sampleRate / 1000L
        val dataBytes = count * 4

        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun intLE(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun shortLE(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())

            out.writeBytes("RIFF")
            intLE(36 + dataBytes)
            out.writeBytes("WAVEfmt ")
            intLE(16)
            shortLE(1) // PCM format
            shortLE(2) // 2 channels
            intLE(sampleRate)
            intLE(sampleRate * 4) // Byte rate
            shortLE(4) // Block align
            shortLE(16) // Bits per sample
            out.writeBytes("data")
            intLE(dataBytes)

            for (i in 0 until count) {
                val relative = i - phase
                val beat = if (relative >= 0) (relative / period).toInt() else -1
                val onset = if (beat >= 0) phase + (beat * period).roundToInt() else Long.MAX_VALUE
                val isClick = i - onset in 0L until 96L
                val value = if (isClick) 20_000 else 0

                val left = if (channel == 0 || channel == 2) value else 0
                val right = if (channel == 1 || channel == 2) value else 0
                shortLE(left)
                shortLE(right)
            }
        }
    }

    fun render(
        fileA: File,
        fileB: File,
        outputWav: File,
        crossfadeMs: Long = 2000L,
        maxDurationSeconds: Int = 30,
        gridA: BeatGrid? = null,
        gridB: BeatGrid? = null,
    ): Long {
        val manager = DefaultAudioPlayerManager().apply {
            registerSourceManager(LocalAudioSourceManager())
            configuration.outputFormat = FORMAT
        }

        val config = MixerConfig().apply {
            this.crossfadeMs = crossfadeMs
            crossfadeEnabled = true
            fadeInMs = 0
            maskMs = 0
            bpmDetectionEnabled = true
            beatmatchEnabled = (gridA != null && gridB != null)
        }

        val mixer = GuildMixer(8888L, manager, config, Executor { })
        val player = manager.createPlayer()
        player.setFilterFactory(PcmFilterFactory { _, _, downstream -> mutableListOf(mixer.newFilter(downstream)) })
        mixer.attachMain(createIPlayer(mixer.guildId, player))

        val trackA = loadTrack(manager, fileA)
        val trackB = loadTrack(manager, fileB)

        if (gridA != null) {
            AnalysisCaches.bpm[trackA.identifier] = BpmDetector.BpmResult(gridA.bpm, gridA.phaseMs, gridA.confidence)
            AnalysisCaches.bpmHead[trackA.identifier] = gridA
            AnalysisCaches.bpmTail[trackA.identifier] = gridA
        }
        if (gridB != null) {
            AnalysisCaches.bpm[trackB.identifier] = BpmDetector.BpmResult(gridB.bpm, gridB.phaseMs, gridB.confidence)
            AnalysisCaches.bpmHead[trackB.identifier] = gridB
            AnalysisCaches.bpmTail[trackB.identifier] = gridB
        }

        player.playTrack(trackA)
        mixer.queueNext(CompletableFuture.completedFuture(trackB))

        val rawPcmFile = File.createTempFile("offline-mix-raw-", ".pcm").apply { deleteOnExit() }
        var totalBytesWritten = 0L

        val buffer = ByteBuffer.allocate(FORMAT.maximumChunkSize())
        val frame = MutableAudioFrame(buffer)
        FileOutputStream(rawPcmFile).use { rawOut ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxDurationSeconds.toLong())
            var frames = 0L
            var handoffCompleted = false

            while (System.nanoTime() < deadline) {
                if (!handoffCompleted && player.playingTrack != null && player.playingTrack !== trackA) {
                    handoffCompleted = true
                }

                if (player.provide(frame)) {
                    val data = frame.data
                    val length = frame.dataLength
                    if (length > 0) {
                        rawOut.write(data, 0, length)
                        totalBytesWritten += length
                    }
                    frames++
                    mixer.drain()
                    if (frames % 5L == 0L) mixer.poll()

                    // If handoff happened and player stopped, we are done
                    if (handoffCompleted && player.playingTrack == null) break
                } else {
                    mixer.poll()
                    if (handoffCompleted && player.playingTrack == null) break
                    Thread.sleep(1)
                }
            }
        }

        mixer.destroy()
        player.destroy()
        manager.shutdown()

        // Pack raw PCM into a standard WAV file
        writeWavHeader(rawPcmFile, outputWav, totalBytesWritten, SAMPLE_RATE)
        rawPcmFile.delete()

        return totalBytesWritten / 4 // 16-bit stereo = 4 bytes per sample frame
    }

    private fun writeWavHeader(rawPcm: File, wavOut: File, dataBytes: Long, sampleRate: Int) {
        val totalDataLen = dataBytes + 36
        DataOutputStream(FileOutputStream(wavOut).buffered()).use { out ->
            fun intLE(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun shortLE(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())

            out.writeBytes("RIFF")
            intLE(totalDataLen.toInt())
            out.writeBytes("WAVEfmt ")
            intLE(16)
            shortLE(1) // PCM
            shortLE(2) // Stereo
            intLE(sampleRate)
            intLE(sampleRate * 4)
            shortLE(4)
            shortLE(16)
            out.writeBytes("data")
            intLE(dataBytes.toInt())

            rawPcm.inputStream().use { input ->
                input.copyTo(out)
            }
        }
    }

    private fun loadTrack(manager: DefaultAudioPlayerManager, file: File): AudioTrack {
        val future = CompletableFuture<AudioTrack>()
        manager.loadItem(file.absolutePath, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { future.complete(track) }
            override fun playlistLoaded(playlist: AudioPlaylist) { future.completeExceptionally(IllegalStateException("unexpected playlist")) }
            override fun noMatches() { future.completeExceptionally(IllegalStateException("missing file: ${file.absolutePath}")) }
            override fun loadFailed(exception: FriendlyException) { future.completeExceptionally(exception) }
        })
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun createIPlayer(guildId: Long, player: AudioPlayer): IPlayer = Proxy.newProxyInstance(
        OfflineMixRenderer::class.java.classLoader,
        arrayOf(IPlayer::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getGuildId" -> guildId
            "getAudioPlayer" -> player
            "getTrack" -> player.playingTrack
            "isPlaying" -> player.playingTrack != null
            "play" -> { player.playTrack(args!![0] as AudioTrack); null }
            "stop" -> { player.stopTrack(); null }
            else -> null
        }
    } as IPlayer
}
