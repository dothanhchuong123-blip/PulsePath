package com.soundsight.ui.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.soundsight.core.EmergencyProfile
import com.soundsight.core.EmergencyStore
import com.soundsight.core.SoundSightStrings
import com.soundsight.ui.FormScreenScaffold
import com.soundsight.ui.settings.SettingSwitchRow
import com.soundsight.ui.theme.LocalHazardColors
import com.soundsight.ui.theme.groupedShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The form behind the post-collision screen.
 *
 * Everything here is optional and the whole feature is off until the rider turns it on, so the
 * screen opens in its "nothing is stored" state and stays useful even if they fill in one field
 * and leave. Saving is explicit rather than per-keystroke: this is the one screen in the app where
 * a half-typed phone number being persisted would be actively harmful.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmergencyProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = SoundSightStrings.current
    val hazard = LocalHazardColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var riderName by remember { mutableStateOf("") }
    var bloodType by remember { mutableStateOf("") }
    var medicalNotes by remember { mutableStateOf("") }
    var contact1Name by remember { mutableStateOf("") }
    var contact1Phone by remember { mutableStateOf("") }
    var contact2Name by remember { mutableStateOf("") }
    var contact2Phone by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val stored = EmergencyStore.profileFlow(context).first()
        enabled = stored.enabled
        riderName = stored.riderName
        bloodType = stored.bloodType
        medicalNotes = stored.medicalNotes
        contact1Name = stored.contact1Name
        contact1Phone = stored.contact1Phone
        contact2Name = stored.contact2Name
        contact2Phone = stored.contact2Phone
        loaded = true
    }

    fun currentProfile() = EmergencyProfile(
        enabled = enabled,
        riderName = riderName,
        bloodType = bloodType,
        medicalNotes = medicalNotes,
        contact1Name = contact1Name,
        contact1Phone = contact1Phone,
        contact2Name = contact2Name,
        contact2Phone = contact2Phone
    )

    FormScreenScaffold(
        title = strings.emergencyTitle,
        modifier = Modifier
            .testTag("emergency_profile_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        onBackContent = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("emergency_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        titleTag = "emergency_title"
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The privacy claim sits at the top, before any field is asked for. A rider deciding
            // whether to type their blood type into a phone app should not have to hunt for it.
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = strings.emergencyPrivacyNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.testTag("emergency_privacy_note")
                        )
                    }
                }
            }

            item {
                // The shared whole-row toggle: one toggleable node with a display-only Switch,
                // so the row and the switch never act as two competing controls. Same pattern and
                // 72dp target as Settings.
                SettingSwitchRow(
                    title = strings.emergencyEnableLabel,
                    supporting = strings.emergencyEnableSub,
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    testTag = "switch_emergency_enabled"
                )
            }

            item { SectionLabel(strings.emergencyYourDetails) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldCard(shape = groupedShape(0, 3)) {
                        OutlinedTextField(
                            value = riderName,
                            onValueChange = {
                                riderName = it.take(EmergencyProfile.MAX_NAME_LENGTH)
                            },
                            label = { Text(strings.emergencyRiderName) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_rider_name")
                        )
                    }

                    FieldCard(shape = groupedShape(1, 3)) {
                        Column {
                            Text(
                                text = strings.emergencyBloodType,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Chips rather than a text field: blood type is a closed set, and
                            // free text here invites a typo in the one field a paramedic reads.
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                EmergencyProfile.BLOOD_TYPES.forEach { type ->
                                    FilterChip(
                                        selected = bloodType == type,
                                        onClick = {
                                            bloodType = if (bloodType == type) "" else type
                                        },
                                        label = { Text(type) },
                                        modifier = Modifier.testTag("chip_blood_$type")
                                    )
                                }
                            }
                        }
                    }

                    FieldCard(shape = groupedShape(2, 3)) {
                        OutlinedTextField(
                            value = medicalNotes,
                            onValueChange = {
                                medicalNotes = it.take(EmergencyProfile.MAX_NOTES_LENGTH)
                            },
                            label = { Text(strings.emergencyMedicalNotes) },
                            placeholder = { Text(strings.emergencyMedicalNotesHint) },
                            minLines = 2,
                            maxLines = 4,
                            supportingText = {
                                Text("${medicalNotes.length}/${EmergencyProfile.MAX_NOTES_LENGTH}")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_medical_notes")
                        )
                    }
                }
            }

            item { SectionLabel(strings.emergencyContact1) }

            item {
                ContactFields(
                    name = contact1Name,
                    phone = contact1Phone,
                    onNameChange = { contact1Name = it.take(EmergencyProfile.MAX_NAME_LENGTH) },
                    onPhoneChange = { contact1Phone = it.take(EmergencyProfile.MAX_PHONE_LENGTH) },
                    nameLabel = strings.emergencyContactName,
                    phoneLabel = strings.emergencyContactPhone,
                    nameTag = "field_contact1_name",
                    phoneTag = "field_contact1_phone"
                )
            }

            item { SectionLabel(strings.emergencyContact2) }

            item {
                ContactFields(
                    name = contact2Name,
                    phone = contact2Phone,
                    onNameChange = { contact2Name = it.take(EmergencyProfile.MAX_NAME_LENGTH) },
                    onPhoneChange = { contact2Phone = it.take(EmergencyProfile.MAX_PHONE_LENGTH) },
                    nameLabel = strings.emergencyContactName,
                    phoneLabel = strings.emergencyContactPhone,
                    nameTag = "field_contact2_name",
                    phoneTag = "field_contact2_phone"
                )
            }

            // Says plainly why the feature might not fire, instead of letting the rider believe
            // it is armed when it is not.
            item {
                if (enabled && contact1Phone.isBlank()) {
                    Text(
                        text = strings.emergencyNeedsContact,
                        style = MaterialTheme.typography.bodySmall,
                        color = hazard.Warning,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .testTag("emergency_needs_contact_warning")
                    )
                }
            }

            item {
                Text(
                    text = strings.emergencyLocationNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            item {
                FilledTonalButton(
                    onClick = {
                        val profile = currentProfile()
                        scope.launch {
                            EmergencyStore.save(context, profile)
                            snackbarHostState.showSnackbar(strings.emergencySaved)
                        }
                    },
                    enabled = loaded,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("btn_emergency_save")
                ) {
                    Text(
                        text = strings.emergencySave,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
                // Confirmed, not immediate. This wipes data the rider typed once and may never
                // look at again, it sits directly under Save, and there is no undo - which is
                // exactly the combination that turns one mis-tap into a silently disarmed safety
                // feature. Discovered by mis-tapping it during device testing.
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_emergency_clear")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = hazard.Critical,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = strings.emergencyClear, color = hazard.Critical)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = hazard.Critical
                    )
                },
                title = { Text(strings.emergencyClearDialogTitle) },
                text = { Text(strings.emergencyClearDialogBody) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            scope.launch {
                                EmergencyStore.clear(context)
                                enabled = false
                                riderName = ""
                                bloodType = ""
                                medicalNotes = ""
                                contact1Name = ""
                                contact1Phone = ""
                                contact2Name = ""
                                contact2Phone = ""
                                snackbarHostState.showSnackbar(strings.emergencyClearConfirm)
                            }
                        },
                        modifier = Modifier.testTag("btn_confirm_clear")
                    ) {
                        Text(strings.emergencyClearDialogConfirm, color = hazard.Critical)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmClear = false },
                        modifier = Modifier.testTag("btn_cancel_clear")
                    ) {
                        Text(strings.emergencyCancel)
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun FieldCard(
    shape: androidx.compose.ui.graphics.Shape,
    content: @Composable () -> Unit
) {
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ContactFields(
    name: String,
    phone: String,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    nameLabel: String,
    phoneLabel: String,
    nameTag: String,
    phoneTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldCard(shape = groupedShape(0, 2)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(nameLabel) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(nameTag)
            )
        }
        FieldCard(shape = groupedShape(1, 2)) {
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                label = { Text(phoneLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(phoneTag)
            )
        }
    }
}
