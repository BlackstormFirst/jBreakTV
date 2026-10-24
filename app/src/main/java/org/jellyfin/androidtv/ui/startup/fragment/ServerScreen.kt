package org.jellyfin.androidtv.ui.startup.fragment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.ui.ExpandableTextView
import org.jellyfin.androidtv.ui.ServerButton
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuItem
import org.jellyfin.androidtv.ui.card.UserCard
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.util.UUID

@Composable
fun ServerScreen(
    server: Server,
    users: List<User>,
    lastUserId: UUID? = null,
    getUserImage: (User) -> String?,
    onUserClick: (User) -> Unit,
    onUserLogout: (User) -> Unit,
    onUserRemove: (User) -> Unit,
    onAddUser: () -> Unit,
    onEditServer: () -> Unit,
    disclaimerText: CharSequence?
) {
    val userFocusRequester = remember { FocusRequester() }
    val addUserFocusRequester = remember { FocusRequester() }

    val targetUser = remember(users, lastUserId) {
        users.find { it.id == lastUserId } ?: users.firstOrNull()
    }

    LaunchedEffect(users, targetUser) {
        if (users.isNotEmpty() && targetUser != null) {
            yield()
            repeat(10) {
                try {
                    userFocusRequester.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(30)
                }
            }
        } else {
            yield()
            repeat(10) {
                try {
                    addUserFocusRequester.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(30)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Notification Area
            if (!server.versionSupported) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFE53935)).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.server_unsupported_notification, server.version ?: "", ServerRepository.recommendedServerVersion.toString()),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (!server.setupCompleted) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFE53935)).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.server_setup_incomplete),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = stringResource(R.string.who_is_watching),
                style = JellyfinTheme.typography.listHeader,
                color = JellyfinTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Users Row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                if (users.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_user_warning),
                        color = JellyfinTheme.colorScheme.onBackground
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRestorer()
                    ) {
                        items(users, key = { it.id }) { user ->
                            val isTargetUser = user.id == targetUser?.id
                            var showMenu by remember { mutableStateOf(false) }

                            Box {
                                UserCard(
                                    image = {
                                        ProfilePicture(
                                            url = getUserImage(user),
                                            iconPadding = PaddingValues(24.dp),
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    },
                                    name = { Text(text = user.name, maxLines = 1) },
                                    onClick = { onUserClick(user) },
                                    onLongClick = { showMenu = true },
                                    modifier = Modifier
                                        .width(110.dp)
                                        .padding(vertical = 8.dp)
                                        .then(if (isTargetUser) Modifier.focusRequester(userFocusRequester) else Modifier)
                                )

                                if (showMenu && user is PrivateUser) {
                                    Popover(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        PopoverMenu {
                                            if (user.accessToken != null) {
                                                PopoverMenuItem(onClick = {
                                                    showMenu = false
                                                    onUserLogout(user)
                                                }) {
                                                    Text(stringResource(R.string.lbl_sign_out))
                                                }
                                            }
                                            PopoverMenuItem(onClick = {
                                                showMenu = false
                                                onUserRemove(user)
                                            }) {
                                                Text(stringResource(R.string.lbl_remove))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Actions Container
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(500.dp)
            ) {
                Button(
                    onClick = onAddUser,
                    modifier = Modifier
                        .padding(8.dp)
                        .weight(1f)
                        .height(51.dp)
                        .focusRequester(addUserFocusRequester)
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_user_add),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(text = stringResource(R.string.add_user))
                }

                ServerButton(
                    icon = {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_house_edit),
                            contentDescription = null,
                        )
                    },
                    name = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    address = { Text(server.address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    version = { Text(server.version.orEmpty(), maxLines = 1) },
                    onClick = onEditServer,
                    modifier = Modifier
                        .padding(8.dp)
                        .weight(1f)
                        .height(51.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Disclaimer
            if (!disclaimerText.isNullOrBlank()) {
                // Using AndroidView to wrap ExpandableTextView since it has specific span rendering
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        ExpandableTextView(context).apply {
                            gravity = android.view.Gravity.CENTER
                            maxLines = 3
                            setPadding(8, 8, 8, 8)
                            text = disclaimerText
                        }
                    },
                    update = { view ->
                        view.text = disclaimerText
                    },
                    modifier = Modifier.width(500.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
