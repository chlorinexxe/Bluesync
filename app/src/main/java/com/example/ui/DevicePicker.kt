package com.example.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.NearbyBleDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothScanOverlay(
    isScanning: Boolean,
    discoveredDevices: List<BluetoothDevice>,
    pairedDevices: List<BluetoothDevice>,
    nearbyBleDevices: List<NearbyBleDevice> = emptyList(),
    onRefreshScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightSpaceBg.copy(alpha = 0.97f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = DeepIndigoGlow),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Find a host", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = PureWhite)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), color = RosePulse, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.BluetoothSearching, contentDescription = null, tint = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isScanning) "Searching…" else "Not searching",
                            color = PureWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = { if (isScanning) onStopScan() else onRefreshScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color.DarkGray else RosePulse)
                    ) {
                        Text(if (isScanning) "Stop" else "Scan", color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SectionLabel("Nearby", CyanGlow) }

                    if (nearbyBleDevices.isEmpty()) {
                        item { EmptySectionHint(if (isScanning) "Listening for nearby phones…" else "Tap Scan to find nearby phones instantly.") }
                    } else {
                        itemsIndexed(nearbyBleDevices, key = { _, item -> item.device.address }) { _, nearby ->
                            DeviceRowItem(
                                device = nearby.device,
                                onClick = { onDeviceSelected(nearby.device) },
                                leadingIcon = Icons.Rounded.Bolt,
                                leadingIconTint = CyanGlow,
                                trailingLabel = "${nearby.rssi} dBm",
                                displayName = nearby.name
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionLabel("Other devices", Color.LightGray)
                    }

                    if (discoveredDevices.isEmpty()) {
                        item { EmptySectionHint("No other devices detected.") }
                    } else {
                        itemsIndexed(discoveredDevices) { _, device ->
                            DeviceRowItem(device = device, onClick = { onDeviceSelected(device) })
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionLabel("Paired devices", Color.LightGray)
                    }

                    if (pairedDevices.isEmpty()) {
                        item { EmptySectionHint("No paired devices found.") }
                    } else {
                        itemsIndexed(pairedDevices) { _, device ->
                            DeviceRowItem(device = device, onClick = { onDeviceSelected(device) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(text = text.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun EmptySectionHint(text: String) {
    Text(
        text = text,
        color = Color.Gray,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        textAlign = TextAlign.Center
    )
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceRowItem(
    device: BluetoothDevice,
    onClick: () -> Unit,
    leadingIcon: ImageVector = Icons.Rounded.Smartphone,
    leadingIconTint: Color = Color.LightGray,
    trailingLabel: String? = null,
    displayName: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(leadingIcon, contentDescription = null, tint = leadingIconTint)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName ?: device.name ?: "Unnamed device",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    color = Color.LightGray.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (trailingLabel != null) {
            Text(
                text = trailingLabel,
                color = CyanGlow,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
                maxLines = 1
            )
        }

        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.Gray)
    }
}
