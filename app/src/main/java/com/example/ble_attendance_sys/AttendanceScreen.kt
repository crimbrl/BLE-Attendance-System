package com.example.ble_attendance_sys

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Presentation only: collection, uploads and CSV export remain owned by the existing code. */
@Composable
fun AttendanceGreenTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF86D6B2), onPrimary = Color(0xFF003824),
            primaryContainer = Color(0xFF164E39), onPrimaryContainer = Color(0xFFB7F1D5),
            background = Color(0xFF101713), surface = Color(0xFF18221C),
            onBackground = Color(0xFFE1E9E3), onSurface = Color(0xFFE1E9E3),
            surfaceVariant = Color(0xFF27372E), onSurfaceVariant = Color(0xFFBDCABF),
            outlineVariant = Color(0xFF3B4B40)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF13694E), onPrimary = Color.White,
            primaryContainer = Color(0xFFE1F2E9), onPrimaryContainer = Color(0xFF124B37),
            background = Color(0xFFF4F7F5), surface = Color.White,
            onBackground = Color(0xFF192D23), onSurface = Color(0xFF192D23),
            surfaceVariant = Color(0xFFEDF2EE), onSurfaceVariant = Color(0xFF56675D),
            outlineVariant = Color(0xFFDCE5DF)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun AttendanceScreen(
    studentName: String,
    studentId: String,
    classCode: String,
    joining: Boolean,
    isCollecting: Boolean,
    scanInterrupted: Boolean,
    classroomName: String,
    beaconName: String,
    deviceAddress: String?,
    uploadMessage: String,
    pendingWindows: Int,
    rejectedWindows: Int,
    sampleCount: Int,
    windowCount: Int,
    lastRssi: Int?,
    lastWindowSummary: String?,
    onSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val status = when {
        joining -> "강의실 확인 중"
        isCollecting && scanInterrupted -> "BLE 스캔 중단"
        isCollecting && sampleCount == 0 -> "비콘 신호 수신 대기"
        isCollecting -> "신호 수집 중"
        else -> "수업 참여 대기"
    }
    val description = when {
        joining -> "수업과 지정된 비콘 정보를 확인하고 있어요."
        isCollecting && scanInterrupted -> "블루투스 상태와 앱 권한을 확인해 주세요."
        isCollecting -> "화면을 꺼도 수집을 이어갑니다."
        else -> "수업에 참여하면 신호 수집을 시작해요."
    }
    val statusColor = if (scanInterrupted && isCollecting) colors.error else colors.primary
    val statusBackground = if (scanInterrupted && isCollecting) colors.errorContainer else colors.primaryContainer

    Scaffold(containerColor = colors.background) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("SKKU 전자출결", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onSettings, enabled = !isCollecting && !joining) { Text("설정") }
            }

            AttendanceCard {
                Text(studentName.ifBlank { "학생 정보를 입력해 주세요" },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (studentId.isBlank()) "오른쪽 위 설정에서 수업 참여를 준비하세요." else "학번 $studentId",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }

            Surface(color = statusBackground, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (joining) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = statusColor, strokeWidth = 2.dp)
                        } else {
                            Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("수집 상태", style = MaterialTheme.typography.labelLarge, color = statusColor)
                    }
                    Text(status, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isCollecting && scanInterrupted) colors.onErrorContainer else colors.onPrimaryContainer)
                    Text(description, style = MaterialTheme.typography.bodyMedium,
                        color = if (isCollecting && scanInterrupted) colors.onErrorContainer else colors.onPrimaryContainer)
                }
            }

            AttendanceCard {
                InfoLine("수업 코드", classCode.ifBlank { "설정 필요" })
                HorizontalDivider(color = colors.outlineVariant)
                InfoLine("강의실", classroomName.ifBlank { "참여 시 자동 확인" })
            }

            // Always visible: important errors must not be hidden inside the details panel.
            if (uploadMessage.isNotBlank()) {
                Text("전송 안내", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Text(uploadMessage, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }

            AttendanceCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("수집 상세", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("전송 대기 ${pendingWindows}개 · 완료 윈도우 ${windowCount}개",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "접기" else "펼치기")
                    }
                }
                if (showDetails) {
                    HorizontalDivider(color = colors.outlineVariant)
                    InfoLine("최근 수신 RSSI", lastRssi?.let { "$it dBm" } ?: "수신 대기")
                    InfoLine("수신 샘플", "${sampleCount}개")
                    InfoLine("집계 간격", "5초")
                    InfoLine("거절 보관", "${rejectedWindows}개")
                    InfoLine("비콘 이름", beaconName.ifBlank { "참여 시 자동 확인" })
                    InfoLine("MAC 주소", deviceAddress?.takeIf { it.isNotBlank() } ?: "미확인")
                    lastWindowSummary?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onExport,
                        enabled = !isCollecting && !joining && sampleCount > 0,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("CSV 저장", Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            Button(onClick = onStart, enabled = !isCollecting && !joining,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) {
                Text(if (joining) "강의실 확인 중…" else "수업 참여 · 수집 시작",
                    style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onStop, enabled = isCollecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("수집 중지")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun AttendanceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AttendanceWaitingPreview() {
    AttendanceGreenTheme {
        AttendanceScreen("장민성", "2021314496", "7125602B", false, false, false,
            "", "", null, "수업 참여 대기", 0, 0, 0, 0, null, null, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AttendanceCollectingPreview() {
    AttendanceGreenTheme {
        AttendanceScreen("장민성", "2021314496", "7125602B", false, true, false,
            "10001", "MOMENTUM 4", null, "미리보기용 전송 안내", 0, 0, 24, 3, -68, null, {}, {}, {}, {})
    }
}
