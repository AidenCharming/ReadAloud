package com.readaloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.readaloud.ui.ReadAloudTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReadAloudTheme {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

@Composable
private fun App(vm: AppViewModel) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600 // Fold inner screen, tablets, landscape
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    // On the wide screen the book list folds away once a book is open so the text gets the room; the reader's list button brings it back
    var listOpen by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(vm.current) { if (vm.current != null) listOpen = false }
    if (about) AboutDialog { about = false }
    BackHandler(enabled = !wide && vm.current != null && tab == 0) { vm.close() }

    // On the narrow screen the reader takes over, so hide the tab bar there
    val readerFullScreen = !wide && vm.current != null && tab == 0
    Scaffold(
        bottomBar = {
            if (!readerFullScreen) NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(if (tab == 0) Icons.Filled.Headphones else Icons.Outlined.Headphones, contentDescription = null) },
                    label = { Text("Listen") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(if (tab == 1) Icons.Filled.Bookmarks else Icons.Outlined.Bookmarks, contentDescription = null) },
                    label = { Text("Bookshelf") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                tab == 1 -> ShelfPane(vm, Modifier.fillMaxSize())
                wide -> Row(Modifier.fillMaxSize()) {
                    if (listOpen || vm.current == null) LibraryPane(vm, Modifier.width(380.dp).fillMaxSize()) { about = true }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (vm.current != null) ReaderPane(vm, showBack = false, listOpen = listOpen, onToggleList = { listOpen = !listOpen })
                        else Text("Pick a book to start listening", Modifier.align(Alignment.Center))
                    }
                }
                vm.current != null -> ReaderPane(vm, showBack = true)
                else -> LibraryPane(vm, Modifier.fillMaxSize()) { about = true }
            }
        }
    }
}
