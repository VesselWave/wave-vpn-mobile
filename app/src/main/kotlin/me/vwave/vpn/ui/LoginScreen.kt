package me.vwave.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(vm: AppViewModel) {
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val busy by vm.loginBusy.collectAsStateWithLifecycle()
    val error by vm.loginError.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Wordmark()
        Spacer(Modifier.height(Dimens.lg))
        Text("Sign in", style = MaterialTheme.typography.displaySmall)
        Text(
            "Sign in with your AirVPN username and password. They're sent only to AirVPN, encrypted, and stored encrypted on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.muted,
        )
        Spacer(Modifier.height(Dimens.sm))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(Dimens.radiusMd),
            colors = waveFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(Dimens.radiusMd),
            colors = waveFieldColors(),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.login(user, password) }),
            trailingIcon = {
                // Under very large text the word would crowd the label out, so it becomes an icon.
                if (LocalDensity.current.fontScale >= 1.5f) {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        LucideIcon(
                            if (showPassword) Lucide.EyeOff else Lucide.Eye,
                            tokens.muted,
                            20.dp,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                } else {
                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show", color = tokens.muted) }
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        error?.let { ErrorBanner(it) }
        PrimaryButton(
            if (busy) "Signing in…" else "Sign in",
            onClick = { vm.login(user, password) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            height = 52.dp,
        )
        Text(
            "Independent app, not made by AirVPN. Uses WireGuard only.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.muted,
        )
    }
}
