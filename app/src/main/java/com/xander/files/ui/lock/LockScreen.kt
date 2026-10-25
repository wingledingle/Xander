package com.xander.files.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.xander.files.R
import com.xander.files.data.lock.AppLock
import kotlinx.coroutines.launch

@Composable
fun LockScreen(lock: AppLock, biometric: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val canBio = biometric && BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    fun bio() {
        val act = ctx as? FragmentActivity ?: return
        BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { lock.unlockedByBiometric() }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(ctx.getString(R.string.app_name))
            .setSubtitle(ctx.getString(R.string.unlock)).setNegativeButtonText(ctx.getString(R.string.use_pin)).build())
    }
    LaunchedEffect(Unit) { if (canBio) bio() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.enter_pin), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(pin, { if (it.length <= 12 && it.all(Char::isDigit)) { pin = it; error = false } }, singleLine = true, isError = error,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
            if (error) Text(stringResource(R.string.wrong_pin), color = MaterialTheme.colorScheme.error)
            Row(Modifier.padding(top = 16.dp)) {
                Button(enabled = pin.length >= 4, onClick = { scope.launch { if (!lock.verifyPin(pin)) { error = true; pin = "" } } }) { Text(stringResource(R.string.unlock)) }
                if (canBio) OutlinedButton(onClick = ::bio, modifier = Modifier.padding(start = 12.dp)) { Icon(Icons.Filled.Fingerprint, null) }
            }
        }
    }
}

@Composable
fun SetPinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.set_pin)) }, text = {
        Column {
            OutlinedTextField(p1, { if (it.length <= 12 && it.all(Char::isDigit)) p1 = it }, label = { Text(stringResource(R.string.pin_4_12)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            OutlinedTextField(p2, { if (it.length <= 12 && it.all(Char::isDigit)) p2 = it }, label = { Text(stringResource(R.string.repeat_pin)) }, singleLine = true,
                isError = p2.isNotEmpty() && p1 != p2, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        }
    }, confirmButton = { TextButton(enabled = p1.length >= 4 && p1 == p2, onClick = { onSet(p1) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
