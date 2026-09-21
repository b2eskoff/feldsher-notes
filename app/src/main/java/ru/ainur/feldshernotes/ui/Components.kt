package ru.ainur.feldshernotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import ru.ainur.feldshernotes.Screen
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ainur.feldshernotes.data.*

val Accent = Color(0xFFD9434D)
val Ink = Color(0xFF252729)
val Muted = Color(0xFF828184)
val Paper = Color(0xFFF7F6F4)
val Tint = Color(0xFFFCECEE)
val Field = Color(0xFFF5F4F3)
val SoftGreen = Color(0xFF497567)
@Composable fun NotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Accent,onPrimary = Color.White,primaryContainer = Tint,onPrimaryContainer = Accent,
        background = Paper,onBackground = Ink,surface = Color.White,onSurface = Ink,surfaceVariant = Field,onSurfaceVariant = Muted,outline = Color(0xFFE7E3E2),secondary = SoftGreen),
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold,fontSize = 32.sp,lineHeight = 39.sp,letterSpacing = (-.7).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold,fontSize = 26.sp,lineHeight = 33.sp,letterSpacing = (-.5).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold,fontSize = 22.sp,lineHeight = 29.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold,fontSize = 18.sp,lineHeight = 25.sp),
            bodyLarge = TextStyle(fontSize = 16.sp,lineHeight = 25.sp),bodyMedium = TextStyle(fontSize = 14.sp,lineHeight = 21.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold,fontSize = 15.sp,lineHeight = 21.sp)),content = content)
}
@Composable fun BrandLogo(small: Boolean = false) {
    Box(Modifier.size(if (small) 32.dp else 48.dp).shadow(4.dp,CircleShape,spotColor = Color(0x221D2028))
        .background(Color.White,CircleShape).padding(if (small) 8.dp else 12.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val p = size.width/3;drawRect(Accent,Offset(p,0f),Size(p,size.height));drawRect(Accent,Offset(0f,p),Size(size.width,p))
        }
    }
}
@Composable fun AppHeader(compact: Boolean,onSearch: () -> Unit,onSection: (Screen) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 22.dp,vertical = if (compact) 4.dp else 12.dp),verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSection(Screen.PROFILE) }, modifier = Modifier.size(48.dp).testTag("profileLogo")) { BrandLogo(compact) };Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
        Text(if (compact) "ЗАПИСКИ" else "ЗАПИСКИ\nФЕЛЬДШЕРА",fontWeight = FontWeight.ExtraBold,fontSize = 12.sp,
            letterSpacing = if (compact) 1.1.sp else 1.6.sp,lineHeight = 19.sp,modifier = Modifier.weight(1f),maxLines = 2,overflow = TextOverflow.Ellipsis)
        IconButton(onSearch,Modifier.size(48.dp)) { Icon(Icons.Outlined.Search,"Поиск вызовов",Modifier.size(23.dp)) }
        SectionMenu(onSection)
    }
}
@Composable fun SectionMenu(onSection: (Screen) -> Unit,enabled: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true },Modifier.size(48.dp).testTag("sectionMenu"),enabled = enabled) {
            Icon(Icons.Outlined.Menu,"Разделы приложения")
        }
        DropdownMenu(expanded && enabled,{ expanded = false },modifier = Modifier.widthIn(max = 260.dp),
            shape = RoundedCornerShape(20.dp),containerColor = Color.White) {
            listOf(Triple(Screen.JOURNAL,"Журнал",Icons.Outlined.EditNote),
                Triple(Screen.REFERENCE,"Справочник",Icons.Outlined.MenuBook),
                Triple(Screen.MEMORY,"Память",Icons.Outlined.Mic),
                Triple(Screen.MORE,"Резервные копии",Icons.Outlined.Backup)).forEach { (screen,label,icon) ->
                DropdownMenuItem(text = { Text(label) },onClick = { expanded = false;onSection(screen) },
                    leadingIcon = { Icon(icon,null,tint = Accent) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("section_${screen.name}"))
            }
        }
    }
}
@Composable fun PageHeader(title: String,compact: Boolean,onBack: () -> Unit,actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp,vertical = if (compact) 0.dp else 6.dp),verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack,Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад") }
        Text(title,Modifier.weight(1f),fontWeight = FontWeight.SemiBold,fontSize = if (compact) 17.sp else 19.sp,maxLines = 1,overflow = TextOverflow.Ellipsis)
        actions()
    }
}
@Composable fun PrimaryButton(text: String,onClick: () -> Unit,modifier: Modifier = Modifier,compact: Boolean = false,icon: ImageVector? = null,enabled: Boolean = true) {
    Button(onClick,modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 58.dp),enabled = enabled,
        shape = RoundedCornerShape(if (compact) 17.dp else 20.dp),contentPadding = PaddingValues(horizontal = 20.dp,vertical = 12.dp)) {
        icon?.let { Icon(it,null,Modifier.size(22.dp));Spacer(Modifier.width(9.dp)) }
        Text(text,fontSize = if (compact) 15.sp else 17.sp,fontWeight = FontWeight.SemiBold)
    }
}
@Composable fun SmallTag(text: String) {
    Text(text,Modifier.background(Field,RoundedCornerShape(8.dp)).padding(horizontal = 8.dp,vertical = 5.dp),
        fontSize = 12.sp,lineHeight = 17.sp,fontWeight = FontWeight.Medium,color = Color(0xFF6F6D70),maxLines = 1,overflow = TextOverflow.Ellipsis)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun CallCard(r: CallRecord,compact: Boolean,onClick: () -> Unit,highlighted: Boolean = false,showDate: Boolean = false) {
    Surface(onClick,Modifier.fillMaxWidth(),shape = RoundedCornerShape(if (compact) 17.dp else 24.dp),color = Color.White,
        shadowElevation = if (compact) 0.dp else 1.dp,border = if (highlighted) androidx.compose.foundation.BorderStroke(1.dp,Accent.copy(alpha = .25f)) else null) {
        if (compact) Row(Modifier.padding(horizontal = 13.dp,vertical = 12.dp),verticalAlignment = Alignment.CenterVertically) {
            r.time?.let { Text(it,Modifier.padding(end = 10.dp),color = Accent,fontWeight = FontWeight.SemiBold,fontSize = 12.sp) }
            Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.displayTitle,fontSize = 14.sp,lineHeight = 20.sp,fontWeight = FontWeight.SemiBold,maxLines = 2,overflow = TextOverflow.Ellipsis)
                if (showDate) Text(shortDate(r.date),fontSize = 11.sp,color = Muted)
            }
            Icon(Icons.Outlined.ChevronRight,null,Modifier.size(16.dp),tint = Muted.copy(alpha = .6f))
        } else Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (r.time != null || showDate) Row(verticalAlignment = Alignment.CenterVertically) {
                r.time?.let { Box(Modifier.size(5.dp).background(Accent,CircleShape));Spacer(Modifier.width(7.dp));Text(it,fontSize = 13.sp,fontWeight = FontWeight.SemiBold,color = Accent) }
                if (showDate) Text((if (r.time != null) "  ·  " else "")+longDate(r.date),fontSize = 12.sp,color = Muted,maxLines = 1)
                Spacer(Modifier.weight(1f));Icon(Icons.Outlined.NorthEast,null,Modifier.size(16.dp),tint = Color(0xFFCECBCC))
            }
            Text(r.displayTitle,style = MaterialTheme.typography.titleMedium,maxLines = 2,overflow = TextOverflow.Ellipsis)
            val person = r.blocks.firstOrNull { it.kind == BlockKind.PERSON && it.hasContent }?.let(::personSummary).orEmpty()
            if (person.isNotBlank()) Text(person,style = MaterialTheme.typography.bodyMedium,color = Muted)
            if (r.previewText.isNotBlank()) Text(r.previewText,style = MaterialTheme.typography.bodyMedium,color = Color(0xFF777579),maxLines = 2,overflow = TextOverflow.Ellipsis)
            val vitals = r.blocks.firstOrNull { it.kind == BlockKind.VITALS && it.hasContent }?.let(::vitalParts).orEmpty().take(2)
            if (vitals.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) { vitals.forEach { SmallTag(it) } }
            val outcome = r.blocks.firstOrNull { it.kind == BlockKind.OUTCOME && it.hasContent }?.let(::blockSummary)
            val medicine = r.blocks.firstOrNull { it.kind == BlockKind.MEDICINES && it.hasContent }?.let(::blockSummary)
            if (outcome != null || medicine != null) Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.CheckCircle,null,Modifier.padding(top = 2.dp).size(15.dp),tint = SoftGreen);Spacer(Modifier.width(7.dp))
                Text(listOfNotNull(medicine,outcome).joinToString(" · "),fontSize = 12.sp,lineHeight = 18.sp,color = SoftGreen,maxLines = 2,overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
fun blockIcon(kind: BlockKind): ImageVector = when(kind) {
    BlockKind.PERSON -> Icons.Outlined.PersonOutline
    BlockKind.VITALS -> Icons.Outlined.MonitorHeart
    BlockKind.CARE -> Icons.Outlined.VolunteerActivism
    BlockKind.DIAGNOSIS -> Icons.Outlined.MenuBook
    BlockKind.MEDICINES -> Icons.Outlined.Medication
    BlockKind.ECG -> Icons.AutoMirrored.Outlined.ShowChart
    BlockKind.PROCEDURES -> Icons.Outlined.Healing
    BlockKind.OUTCOME -> Icons.Outlined.CheckCircle
    BlockKind.NOTE -> Icons.AutoMirrored.Outlined.Notes
}
