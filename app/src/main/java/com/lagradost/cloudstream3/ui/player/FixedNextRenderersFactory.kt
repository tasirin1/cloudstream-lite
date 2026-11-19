package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * The lite-only build does not ship nextlib/FFmpeg at all (this used
 * to be the lite flavor's replacement for the nextlib-based factory):
 * software decoding stays off (the TV default upstream as well) and
 * playback uses the platform hardware decoders via
 * [DefaultRenderersFactory]. Only the FFmpeg extension renderers are
 * absent, so the software decoding setting simply has no extra
 * effect.
 */
@UnstableApi
class FixedNextRenderersFactory(
    context: Context,
    private val compressor: DynamicRangeCompressor? = null,
) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        out.add(TextRenderer(output, outputLooper))
    }

    @OptIn(UnstableApi::class)
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val activeCompressor = compressor
            ?: return super.buildAudioSink(
                context,
                enableFloatOutput,
                enableAudioTrackPlaybackParams
            )

        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(activeCompressor))
            .build()
    }
}

@UnstableApi
open class FixedNextRenderersFactoryWithoutVideo(
    context: Context,
    private val compressor: DynamicRangeCompressor? = null,
) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        out.add(TextRenderer(output, outputLooper))
    }

    @OptIn(UnstableApi::class)
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink? {
        val activeCompressor = compressor
            ?: return super.buildAudioSink(
                context,
                enableFloatOutput,
                enableAudioTrackPlaybackParams
            )

        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(activeCompressor))
            .build()
    }

    companion object {
        const val TAG = "NextRenderersFactory"
    }
}
