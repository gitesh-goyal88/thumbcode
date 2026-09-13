package `in`.thumbcode.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.thumbcode.receiver.Frame
import `in`.thumbcode.receiver.Settings
import `in`.thumbcode.receiver.Stage
import `in`.thumbcode.receiver.Verdict

@Composable
fun ScanScreen(
    frame: Frame,
    verdict: Verdict?,
    checking: Boolean,
    onOpenSettings: () -> Unit,
    onScanAgain: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Paper).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ThumbCode", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "Settings",
                color = Dim,
                fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(6.dp),
            )
        }

        preview(
            Modifier.fillMaxWidth().height(150.dp)
                .clip(RoundedCornerShape(12.dp)).background(Panel)
        )

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            CanvasOverlay(frame, Modifier.fillMaxSize())
        }

        StatusStrip(frame)

        when {
            checking -> Banner("Asking the issuing office", Dim, Panel)
            verdict != null -> VerdictBlock(verdict, onScanAgain)
            else -> Banner(frame.note, Dim, Panel)
        }
    }
}

@Composable
private fun StatusStrip(frame: Frame) {
    val id = frame.docId
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Chip(if (frame.stage == Stage.SEARCHING) "corners ${frame.candidates}/4" else "corners locked",
            if (frame.stage == Stage.SEARCHING) Dim else Locked)
        frame.sideRatio?.let { Chip("ratio %.3f".format(it), Dim) }
        frame.contrast?.let { Chip("wedge Δ%.0f".format(it), if (it < 40) Warn else Dim) }
        if (id != null) Chip("crc ok", SetBit)
        frame.spots?.let { Chip("k=${it.size}", SpotOn) }
    }
    if (frame.mid != null && frame.high != null) {
        Text(
            "midtones %.2f / %.2f%s".format(
                frame.mid, frame.high,
                if (frame.copySuspect) "  suspected photocopy" else "",
            ),
            color = if (frame.copySuspect) Warn else Dim,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun VerdictBlock(verdict: Verdict, onScanAgain: () -> Unit) {
    when (verdict) {
        is Verdict.Verified -> Panelled(Good) {
            Text("Genuine", color = Good, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text(verdict.docType, color = Ink, fontSize = 15.sp)
            DetailRow("Document ID", verdict.docId)
            if (verdict.holderName.isNotEmpty()) DetailRow("Holder name", verdict.holderName)
            DetailRow("Issuing office", "${verdict.officeName} (${verdict.officeCode})")
            DetailRow("Issue date", verdict.issueDate)
            if (verdict.referenceNo.isNotEmpty()) DetailRow("Reference no.", verdict.referenceNo)
            else DetailRow("Reference", verdict.reference)
            if (verdict.particulars.isNotEmpty()) DetailRow("Particulars", verdict.particulars)
            if (!verdict.signed) {
                Text("Recorded without an operator signature.", color = Warn, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Again(onScanAgain)
        }
        is Verdict.Revoked -> Panelled(Bad) {
            Text("Revoked", color = Bad, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text("This code was genuine and has since been withdrawn.", color = Ink, fontSize = 14.sp)
            if (verdict.docType.isNotEmpty()) Text(verdict.docType, color = Ink, fontSize = 15.sp)
            DetailRow("Document ID", verdict.docId)
            if (verdict.holderName.isNotEmpty()) DetailRow("Holder name", verdict.holderName)
            if (verdict.officeName.isNotEmpty()) DetailRow("Issuing office", "${verdict.officeName} (${verdict.officeCode})")
            if (verdict.issueDate.isNotEmpty()) DetailRow("Issue date", verdict.issueDate)
            if (verdict.referenceNo.isNotEmpty()) DetailRow("Reference no.", verdict.referenceNo)
            else if (verdict.reference.isNotEmpty()) DetailRow("Reference", verdict.reference)
            if (verdict.particulars.isNotEmpty()) DetailRow("Particulars", verdict.particulars)
            DetailRow("Reason", verdict.reason)
            if (verdict.at.isNotEmpty()) DetailRow("Revoked at", verdict.at)
            if (!verdict.signed) {
                Text("Recorded without an operator signature.", color = Warn, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Again(onScanAgain)
        }
        is Verdict.Mismatch -> Panelled(Bad) {
            Text("Does not match the register", color = Bad, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            DetailRow("Document ID", verdict.docId)
            Text(
                "The document number is real but the spot pattern is wrong. " +
                    "That is what a redrawn code looks like.",
                color = Ink, fontSize = 14.sp,
            )
            Again(onScanAgain)
        }
        is Verdict.Unknown -> Panelled(Bad) {
            Text("Not on the register", color = Bad, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            DetailRow("Document ID", verdict.docId)
            Text("No document was ever issued with this number.", color = Ink, fontSize = 14.sp)
            Again(onScanAgain)
        }
        is Verdict.Failed -> Panelled(Warn) {
            Text("Could not check", color = Warn, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(verdict.message, color = Dim, fontSize = 13.sp)
            Again(onScanAgain)
        }
    }
}

@Composable
private fun Again(onScanAgain: () -> Unit) {
    Text(
        "Scan another",
        color = Ink, fontSize = 14.sp,
        modifier = Modifier.padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Rule, RoundedCornerShape(8.dp))
            .clickable(onClick = onScanAgain)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Dim, fontSize = 13.sp)
        Text(
            value.ifEmpty { "—" },
            color = Ink,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Panelled(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun Banner(text: String, color: Color, bg: Color) {
    Text(
        text, color = color, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)).background(bg).padding(14.dp),
    )
}

@Composable
fun SettingsScreen(settings: Settings, onDone: () -> Unit) {
    var url by remember { mutableStateOf(settings.projectUrl) }
    var key by remember { mutableStateOf(settings.anonKey) }
    var place by remember { mutableStateOf(settings.place) }

    Column(
        Modifier.fillMaxSize().background(Paper).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Field("Supabase project URL", url, "https://abcdefgh.supabase.co") { url = it }
        Field("Anon key", key, "eyJhbGciOi...") { key = it }
        Field("Where this phone is checking", place, "Sub-registrar counter 3, Agra") { place = it }
        Text(
            "The place label is what makes one certificate turning up in two towns " +
                "visible in the office's scan log.",
            color = Dim, fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Save",
            color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Locked)
                .clickable {
                    settings.projectUrl = url
                    settings.anonKey = key
                    settings.place = place
                    onDone()
                }
                .padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Dim, fontSize = 13.sp)
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Panel)
                .border(1.dp, Rule, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty()) Text(hint, color = Rule, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(Locked),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
