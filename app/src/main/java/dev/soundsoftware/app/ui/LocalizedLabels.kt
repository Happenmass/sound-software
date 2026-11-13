package dev.soundsoftware.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.soundsoftware.app.R
import dev.soundsoftware.app.device.SyncTransport
import dev.soundsoftware.core.export.ExportFormat
import dev.soundsoftware.core.model.SceneTemplate
import dev.soundsoftware.core.model.Speaker

internal val SyncTransport.labelRes: Int
    get() = when (this) {
        SyncTransport.BLUETOOTH -> R.string.bluetooth
        SyncTransport.WIFI -> R.string.wifi_transfer
    }

internal val SceneTemplate.titleRes: Int
    get() = when (this) {
        SceneTemplate.AUTO -> R.string.template_auto
        SceneTemplate.MEETING -> R.string.template_meeting
        SceneTemplate.INTERVIEW -> R.string.template_interview
        SceneTemplate.JOB_INTERVIEW -> R.string.template_job_interview
        SceneTemplate.LECTURE -> R.string.template_lecture
        SceneTemplate.TRANSCRIPT_ONLY -> R.string.template_transcript_only
    }

internal val SceneTemplate.subtitleRes: Int
    get() = when (this) {
        SceneTemplate.AUTO -> R.string.template_auto_subtitle
        SceneTemplate.MEETING -> R.string.template_meeting_subtitle
        SceneTemplate.INTERVIEW -> R.string.template_interview_subtitle
        SceneTemplate.JOB_INTERVIEW -> R.string.template_job_interview_subtitle
        SceneTemplate.LECTURE -> R.string.template_lecture_subtitle
        SceneTemplate.TRANSCRIPT_ONLY -> R.string.template_transcript_only_subtitle
    }

internal val SceneTemplate.keyPointsRes: Int
    get() = when (this) {
        SceneTemplate.INTERVIEW -> R.string.interview_points
        SceneTemplate.JOB_INTERVIEW -> R.string.job_interview_points
        SceneTemplate.LECTURE -> R.string.lecture_points
        else -> R.string.key_points
    }

@Composable
internal fun Speaker.localizedName(): String = name ?: stringResource(R.string.speaker_name, index)

@Composable
internal fun ExportFormat.localizedTitle(): String = when (this) {
    ExportFormat.SRT -> stringResource(R.string.subtitles)
    ExportFormat.TEXT -> stringResource(R.string.plain_text)
    ExportFormat.JSON -> stringResource(R.string.structured_data)
    else -> title
}
