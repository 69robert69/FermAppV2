package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ro.fermapp.app.Navigator
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.SendResult
import ro.fermapp.app.data.body
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.features.ParcelPicker
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.IconAction
import ro.fermapp.app.ui.OfflineBanner
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt
import kotlin.time.Clock

private data class Message(val id: String, val mine: Boolean, val content: String, val note: String? = null)

@Serializable
private data class ChatMessage(val id: String, val content: String, val source: String)

@Serializable
private data class ChatResult(val conversationId: String, val message: ChatMessage)

private val SUGGESTIONS = listOf(
    "Which crop do you recommend for next season?",
    "Are conditions good for spraying herbicide this week?",
    "What are the main risks on this parcel?",
)

/** The AI tab: the server's assistant answers from the farm database, for the chosen parcel. */
@Composable
fun AssistantScreen(navigator: Navigator) {
    var parcelId by remember { mutableStateOf<String?>(null) }
    var conversationId by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<Message>() }
    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    LaunchedEffect(messages.size, pending) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size)
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || pending) return
        val stamp = Clock.System.now().toEpochMilliseconds()
        messages.add(Message("user-$stamp", true, message))
        draft = ""
        pending = true
        scope.launch {
            try {
                val result = Api.post("/ai/chat", body("message" to message, "conversationId" to conversationId, "parcelId" to parcelId), offline = false)
                if (result !is SendResult.Done) throw IllegalStateException("offline")
                val answer = AppJson.decodeFromJsonElement(ChatResult.serializer(), result.value)
                conversationId = answer.conversationId
                messages.add(
                    Message(
                        answer.message.id,
                        false,
                        answer.message.content,
                        if (answer.message.source == "rules") "Summary calculated from the parcel data" else null,
                    ),
                )
            } catch (error: Throwable) {
                messages.add(Message("error-$stamp", false, errorMessage(error)))
            } finally {
                pending = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(FarmColors.loam).statusBarsPadding().imePadding()) {
        OfflineBanner()
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Txt("Assistant", TextVariant.Display)
            ParcelPicker(parcelId, { parcelId = it }, "Analyse parcel")
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Space.lg, end = Space.lg, bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Txt("Choose a parcel and ask about rotation, soil, weather or field work.", color = FarmColors.straw)
                        SUGGESTIONS.forEach { suggestion ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(FarmColors.furrow).clickable { send(suggestion) }
                                    .heightIn(min = 44.dp).padding(Space.md),
                                contentAlignment = Alignment.CenterStart,
                            ) { Txt(suggestion) }
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { message ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (message.mine) FarmColors.wheat else FarmColors.furrow).padding(Space.md),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Txt(message.content, color = if (message.mine) FarmColors.wheatInk else FarmColors.chaff)
                        message.note?.let { Txt(it, TextVariant.Small) }
                    }
                }
            }
            if (pending) item { CircularProgressIndicator(color = FarmColors.wheat) }
        }
        Row(Modifier.fillMaxWidth().background(FarmColors.furrow).padding(Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Field(draft, { if (it.length <= 4000) draft = it }, "Your question", Modifier.weight(1f), singleLine = false)
            IconAction(Icons.AutoMirrored.Outlined.Send, "Send question", { send(draft) }, tint = FarmColors.wheat)
        }
    }
}
