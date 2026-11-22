package com.oneuiclone.home.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SearchBlurSettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Search & Blur Settings") })
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Card 1: App Drawer
                        SettingsCard(title = "App Drawer") {
                            SliderRow("Background Opacity", prefs.drawerBgOpacity) { prefs.drawerBgOpacity = it }
                            SliderRow("Background Blur", prefs.drawerBgBlur) { prefs.drawerBgBlur = it }
                            SliderRow("Search Bar Opacity", prefs.drawerSearchBarOpacity) { prefs.drawerSearchBarOpacity = it }
                            SliderRow("Search Bar Blur", prefs.drawerSearchBarBlur) { prefs.drawerSearchBarBlur = it }
                            SliderRow("Border Size", prefs.drawerSearchBorderSize, max = 50) { prefs.drawerSearchBorderSize = it }
                            SliderRow("Border Opacity", prefs.drawerSearchBorderOpacity) { prefs.drawerSearchBorderOpacity = it }
                        }

                        // Card 2: Search Pill (Home)
                        SettingsCard(title = "Home Search Pill") {
                            SliderRow("Background Opacity", prefs.homePillBgOpacity) { prefs.homePillBgOpacity = it }
                            SliderRow("Background Blur", prefs.homePillBgBlur) { prefs.homePillBgBlur = it }
                            SliderRow("Border Size", prefs.homePillBorderSize, max = 50) { prefs.homePillBorderSize = it }
                            SliderRow("Border Opacity", prefs.homePillBorderOpacity) { prefs.homePillBorderOpacity = it }
                        }

                        // Card 3: App Search (Overlay)
                        SettingsCard(title = "App Search") {
                            SliderRow("Background Opacity", prefs.appSearchBgOpacity) { prefs.appSearchBgOpacity = it }
                            SliderRow("Background Blur", prefs.appSearchBgBlur) { prefs.appSearchBgBlur = it }
                            SliderRow("Search Bar Opacity", prefs.appSearchBarOpacity) { prefs.appSearchBarOpacity = it }
                            SliderRow("Search Bar Blur", prefs.appSearchBarBlur) { prefs.appSearchBarBlur = it }
                            SliderRow("Border Size", prefs.appSearchBorderSize, max = 50) { prefs.appSearchBorderSize = it }
                            SliderRow("Border Opacity", prefs.appSearchBorderOpacity) { prefs.appSearchBorderOpacity = it }
                        }

                        // Card 4: App Search Cards
                        SettingsCard(title = "App Search Cards") {
                            SliderRow("Background Opacity", prefs.appSearchCardsBgOpacity) { prefs.appSearchCardsBgOpacity = it }
                            SliderRow("Background Blur", prefs.appSearchCardsBgBlur) { prefs.appSearchCardsBgBlur = it }
                            SliderRow("Border Size", prefs.appSearchCardsBorderSize, max = 50) { prefs.appSearchCardsBorderSize = it }
                            SliderRow("Border Opacity", prefs.appSearchCardsBorderOpacity) { prefs.appSearchCardsBorderOpacity = it }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = title, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Divider(modifier = Modifier.padding(bottom = 8.dp))
                content()
            }
        }
    }

    @Composable
    private fun SliderRow(label: String, value: Int, max: Int = 100, onValueChange: (Int) -> Unit) {
        var sliderValue by remember { mutableStateOf(value.toFloat()) }
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = label, fontSize = 14.sp)
                Text(text = "${sliderValue.toInt()}", fontSize = 14.sp)
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onValueChange(it.toInt())
                },
                valueRange = 0f..max.toFloat(),
                steps = max
            )
        }
    }
}
