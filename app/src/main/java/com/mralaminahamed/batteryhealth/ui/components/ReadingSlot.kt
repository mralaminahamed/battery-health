package com.mralaminahamed.batteryhealth.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object ReadingSlotTags {
    const val AVAILABLE = "reading-available"
    const val UNAVAILABLE = "reading-unavailable"
}

/**
 * The only sanctioned way to render a metric. Content is invoked exclusively for
 * [Reading.Available], so an absent metric cannot be styled as data by accident.
 */
@Composable
fun <T> ReadingSlot(
    reading: Reading<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T, Source) -> Unit,
) {
    when (reading) {
        is Reading.Available -> Row(
            modifier = modifier.testTag(ReadingSlotTags.AVAILABLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(reading.value, reading.source)
        }

        Reading.Unsupported -> Reason("Not available on this device", modifier)
        Reading.NeedsShizuku -> Reason("Needs Shizuku", modifier)
        Reading.NotYetMeasured -> Reason("Measuring", modifier)
    }
}

@Composable
private fun Reason(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalOneUiColors.current.textSecondary,
        modifier = modifier.testTag(ReadingSlotTags.UNAVAILABLE),
    )
}
