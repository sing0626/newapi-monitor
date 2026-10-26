package com.newapimonitor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newapimonitor.ui.theme.InnerFillColor

/**
 * Skeleton（.skel + shimmer 掃光）：底色塊 + 滑動漸變光帶。
 * 首次載入（未有任何快照）時映返真實佈局，避免白屏。
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    radius: Dp = 8.dp,
) {
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "x",
    )
    Box(modifier.clip(RoundedCornerShape(radius)).background(InnerFillColor)) {
        Canvas(Modifier.matchParentSize()) {
            val band = (size.width.coerceAtLeast(1f)) * 0.7f
            val travel = size.width + band
            val sx = ((x + 1f) / 2f) * travel - band
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0x21FFFFFF), Color.Transparent),
                    startX = sx,
                    endX = sx + band,
                ),
                topLeft = Offset.Zero,
                size = size,
            )
        }
    }
}

/** 儀表板首次載入 skeleton（js renderDashboard 骨架） */
@Composable
fun SkeletonDashboard() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 餘額卡：標題列 + 大數字 + 兩格 cell
        GlassCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.width(92.dp).height(12.dp))
                SkeletonBox(Modifier.width(64.dp).height(22.dp), radius = 11.dp)
            }
            Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(44.dp), radius = 10.dp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.weight(1f).height(46.dp), radius = 10.dp)
                SkeletonBox(Modifier.weight(1f).height(46.dp), radius = 10.dp)
            }
        }
        // 趨勢卡
        GlassCard {
            SkeletonBox(Modifier.width(92.dp).height(12.dp))
            Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(76.dp), radius = 10.dp)
        }
        // 模型卡：donut 圓 + 圖例 + bar 軌道
        GlassCard {
            SkeletonBox(Modifier.width(92.dp).height(12.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBox(Modifier.size(112.dp), radius = 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth().height(12.dp), radius = 6.dp)
                    SkeletonBox(Modifier.fillMaxWidth(0.82f).height(12.dp), radius = 6.dp)
                    SkeletonBox(Modifier.fillMaxWidth(0.58f).height(12.dp), radius = 6.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(8.dp), radius = 4.dp)
            Spacer(Modifier.height(10.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.86f).height(8.dp), radius = 4.dp)
        }
        // 流水
        GlassCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(3) {
                SkeletonLogRow()
            }
        }
    }
}

/** 日誌頁 / 流水 row skeleton */
@Composable
fun SkeletonLogRow() {
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp), radius = 6.dp)
        SkeletonBox(Modifier.fillMaxWidth(0.75f).height(10.dp), radius = 5.dp)
    }
}
