package ro.fermapp.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ro.fermapp.app.data.Api
import ro.fermapp.app.data.AppJson
import ro.fermapp.app.data.Auth
import ro.fermapp.app.data.SendResult
import ro.fermapp.app.data.Session
import ro.fermapp.app.data.body
import ro.fermapp.app.data.errorMessage
import ro.fermapp.app.theme.FarmColors
import ro.fermapp.app.theme.Space
import ro.fermapp.app.ui.Field
import ro.fermapp.app.ui.TextVariant
import ro.fermapp.app.ui.Txt

@Composable
fun AuthScreen() {
    var registering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var farmName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                val payload = if (registering) body("email" to email, "password" to password, "fullName" to fullName, "farmName" to farmName) else body("email" to email, "password" to password)
                val result = Api.post("/auth/${if (registering) "register" else "login"}", payload, offline = false)
                if (result is SendResult.Done) Auth.setSession(AppJson.decodeFromJsonElement(Session.serializer(), result.value))
            } catch (problem: Throwable) {
                error = errorMessage(problem)
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(FarmColors.loam).imePadding().verticalScroll(rememberScrollState()).padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Spacer(Modifier.height(48.dp))
        Txt("FermApp", TextVariant.Display, color = FarmColors.wheat)
        Txt(
            if (registering) "Create your account and your first farm." else "Your farm’s parcels, field work and water, in one place.",
            color = FarmColors.straw,
        )
        Spacer(Modifier.height(Space.lg))
        if (registering) {
            Field(fullName, { fullName = it }, "Your name")
            Field(farmName, { farmName = it }, "Farm name")
        }
        Field(email, { email = it.trim() }, "Email", keyboard = KeyboardType.Email)
        Field(password, { password = it }, "Password", keyboard = KeyboardType.Password, password = true)
        if (registering) Txt("The password must be at least 10 characters long.", TextVariant.Small)
        error?.let { Txt(it, TextVariant.Small, color = FarmColors.rust) }
        Button(onClick = { submit() }, enabled = !busy && email.isNotEmpty() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = FarmColors.wheatInk, strokeWidth = 2.dp) else Text(if (registering) "Create account" else "Sign in")
        }
        TextButton(onClick = { registering = !registering; error = null }, modifier = Modifier.fillMaxWidth()) {
            Text(if (registering) "I already have an account" else "Create a new account")
        }
    }
}
