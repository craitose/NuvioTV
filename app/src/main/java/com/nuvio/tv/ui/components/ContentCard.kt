package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContentCard(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }

    val cardWidth = when (item.posterShape) {
        PosterShape.POSTER -> 120.dp
        PosterShape.LANDSCAPE -> 240.dp
        PosterShape.SQUARE -> 140.dp
    }
    val cardHeight = when (item.posterShape) {
        PosterShape.POSTER -> 180.dp
        PosterShape.LANDSCAPE -> 135.dp
        PosterShape.SQUARE -> 140.dp
    }

    Column(
        modifier = modifier.width(cardWidth)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(
                shape = RoundedCornerShape(8.dp)
            ),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(3.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(8.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.08f
            ),
            glow = CardDefaults.glow(
                focusedGlow = Glow(
                    elevation = 8.dp,
                    elevationColor = NuvioColors.FocusRing.copy(alpha = 0.3f)
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
            ) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                item.imdbRating?.let { rating ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NuvioColors.Background.copy(alpha = 0.85f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "★",
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.extendedColors.rating
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = String.format("%.1f", rating),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            item.releaseInfo?.let { release ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = release,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}
