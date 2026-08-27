package com.nonamevpn.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nonamevpn.app.core.AppLog
import com.nonamevpn.app.deploy.DeployTarget
import com.nonamevpn.app.deploy.ProvisionApi
import com.nonamevpn.app.profile.ProfileRepository
import com.nonamevpn.app.ui.components.AppSectionCard
import kotlinx.coroutines.launch

@Composable
fun UsersScreen(
    target: DeployTarget,
    profiles: ProfileRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val publicHost = target.publicHost.ifBlank { target.host }

    var users by remember { mutableStateOf<List<ProvisionApi.ProvisionUser>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var createdJson by remember { mutableStateOf<String?>(null) }
    var createdName by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            status = null
            ProvisionApi.listUsers(publicHost)
                .onSuccess {
                    users = it
                    status = if (it.isEmpty()) "Пользователей пока нет" else null
                }
                .onFailure {
                    status = "Ошибка списка: ${it.message}"
                    AppLog.e("Users", it.message ?: "list failed")
                }
            loading = false
        }
    }

    LaunchedEffect(publicHost) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
            }
            Text(
                "Пользователи",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "provision http://$publicHost:9100 — создание по имени (бесплатно).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Создать пользователя", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Имя") },
                singleLine = true,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = {
                    scope.launch {
                        creating = true
                        status = null
                        createdJson = null
                        ProvisionApi.createUser(publicHost, newName)
                            .onSuccess { (profile, raw) ->
                                createdJson = raw
                                createdName = profile.name
                                newName = ""
                                status = "Создан: ${profile.name} (hostId=${profile.hostId})"
                                AppLog.i("Users", "Created ${profile.name}")
                                refresh()
                            }
                            .onFailure {
                                status = "Ошибка создания: ${it.message}"
                                AppLog.e("Users", it.message ?: "create failed")
                            }
                        creating = false
                    }
                },
                enabled = !creating && newName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (creating) "Создаём…" else "Создать")
            }
        }

        createdJson?.let { json ->
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Профиль: ${createdName.orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    json,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("profile", json))
                            status = "Скопировано в буфер"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Copy") }
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching { profiles.importJson(json) }
                                    .onSuccess {
                                        status = "Сохранено в профили: ${it.name}"
                                        AppLog.i("Users", "Saved profile ${it.name}")
                                    }
                                    .onFailure {
                                        status = "Не удалось сохранить: ${it.message}"
                                    }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Сохранить в профили") }
                }
            }
        }

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Список", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "…" else "Обновить")
                }
            }
            if (users.isEmpty() && !loading) {
                Text("Пусто", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            users.forEach { u ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(u.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "hostId=${u.hostId} · ${u.deviceId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        status?.let {
            Text(
                it,
                color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
