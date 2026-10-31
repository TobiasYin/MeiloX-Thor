package com.ljyh.mei.thor

import com.ljyh.mei.data.model.melox.AccountConnectionType
import com.ljyh.mei.thor.account.ThorAccountRoute
import com.ljyh.mei.thor.model.ThorCollection
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.model.ThorItemKey
import com.ljyh.mei.thor.state.*
import com.ljyh.mei.ui.screen.account.OfficialNeteasePage
import org.junit.Assert.*
import org.junit.Test

class ThorEmbeddedNavigationTest {
    @Test
    fun formerAccountAndPlaybackEntriesResolveInsideTheThorStack() {
        val expected = mapOf(
            "account/login" to account(ThorAccountRoute.Login),
            "account/home" to account(ThorAccountRoute.Home),
            "account/security" to account(ThorAccountRoute.Security),
            "account/security/bindings" to account(ThorAccountRoute.Bindings),
            "account/security/devices" to account(ThorAccountRoute.Devices),
            "account/security/authorizations" to account(ThorAccountRoute.Authorizations),
            "setting/play" to ThorPage.AudioSettings,
            "setting/equalizer" to ThorPage.Equalizer,
        )
        for ((route, page) in expected) {
            assertEquals("$route must resolve before the legacy Activity launcher", page, thorInternalPageForRoute(route))
            assertEquals(page, thorInternalPageForRoute("/$route/"))
        }
    }

    @Test
    fun unrelatedAndMalformedRoutesCannotMasqueradeAsEmbeddedAccountPages() {
        for (route in listOf("", "about", "download_manage", "local_music", "account", "account/listening_stats", "account/security/unknown", "setting/play/unknown")) {
            assertNull(route, thorInternalPageForRoute(route))
        }
    }

    @Test
    fun accountLeafKeysDistinguishWebDestinationsAndConnectionLists() {
        val pages = listOf(
            account(ThorAccountRoute.Web(OfficialNeteasePage.PhoneBinding)),
            account(ThorAccountRoute.Web(OfficialNeteasePage.ThirdPartyBinding)),
            account(ThorAccountRoute.Connections(41, AccountConnectionType.Following)),
            account(ThorAccountRoute.Connections(41, AccountConnectionType.Followers)),
            account(ThorAccountRoute.Connections(42, AccountConnectionType.Following)),
        )
        assertEquals(pages.size, pages.map { it.key }.distinct().size)
    }

    @Test
    fun embeddedChildrenAreImmersiveAndShouldersCannotEscapeToAnotherTab() {
        val origin = myRoot()
        assertTrue(origin.isRoot)
        for (page in listOf(account(ThorAccountRoute.Login), account(ThorAccountRoute.Bindings), ThorPage.AudioSettings, ThorPage.Equalizer)) {
            val child = origin.push(page)
            assertFalse("Embedded children must hide the root-only sidebar", child.isRoot)
            assertEquals(child, child.switchTab(1))
            assertEquals(child, child.switchTab(-1))
            assertEquals(origin, child.back())
        }
    }

    @Test
    fun equalizerBackReturnsThroughAudioSettingsToTheOriginalMyPage() {
        val origin = myRoot()
        val audio = origin.push(ThorPage.AudioSettings)
        val equalizer = audio.push(ThorPage.Equalizer)
        assertEquals(audio, equalizer.back())
        assertEquals(origin, equalizer.back()?.back())
        assertTrue(checkNotNull(equalizer.back()?.back()).isRoot)
    }

    @Test
    fun bindingWebBackReturnsThroughTheNativeSecurityStack() {
        val home = ThorNavigation(tab = ThorTab.My).push(account(ThorAccountRoute.Home))
        val security = home.push(account(ThorAccountRoute.Security))
        val bindings = security.push(account(ThorAccountRoute.Bindings))
        val web = bindings.push(account(ThorAccountRoute.Web(OfficialNeteasePage.ThirdPartyBinding)))
        assertEquals(bindings, web.back())
        assertEquals(security, web.back()?.back())
        assertEquals(home, web.back()?.back()?.back())
    }

    @Test
    fun loggingInFromMyRemovesEveryAuthLeafAndShowsOneAccountHome() {
        val origin = myRoot()
        val loggingIn = origin.push(account(ThorAccountRoute.Login))
            .push(account(ThorAccountRoute.MobileLogin))
            .push(account(ThorAccountRoute.WebLogin))
            .push(account(ThorAccountRoute.CookieLogin))
        val completed = loggingIn.completeAccountLogin()
        assertEquals(origin.push(account(ThorAccountRoute.Home)), completed)
        assertEquals(origin, completed.back())
        assertEquals(completed, completed.completeAccountLogin())
    }

    @Test
    fun loginFromAProtectedLibraryListReturnsToThatExactListAndKeepsOtherTabs() {
        val origin = ThorNavigation(tab = ThorTab.Library, stacks = mapOf(
            ThorTab.Library to listOf(ThorPage.Root(ThorTab.Library), ThorPage.Collection(ThorCollection.LikedSongs)),
            ThorTab.My to listOf(ThorPage.Root(ThorTab.My), ThorPage.Appearance),
        ))
        val completed = origin.push(account(ThorAccountRoute.Login))
            .push(account(ThorAccountRoute.WebLogin)).completeAccountLogin()
        assertEquals(origin, completed)
        assertEquals(ThorTab.Library, completed.tab)
        assertEquals(ThorPage.Collection(ThorCollection.LikedSongs), completed.page)
    }

    @Test
    fun reauthenticationReturnsToTheExistingAccountPageWithoutDuplicatingHome() {
        val home = ThorNavigation(tab = ThorTab.My).push(account(ThorAccountRoute.Home))
        for (route in listOf(ThorAccountRoute.Home, ThorAccountRoute.Security, ThorAccountRoute.Bindings)) {
            val caller = home.push(account(route))
            val completed = caller.push(account(ThorAccountRoute.Login))
                .push(account(ThorAccountRoute.CookieLogin)).completeAccountLogin()
            assertEquals(caller, completed)
            assertEquals(1, completed.stacks[ThorTab.My].orEmpty().count { it == account(ThorAccountRoute.Home) })
        }
    }

    @Test
    fun aDirectAuthLeafCompletesTheSameWayAndUnrelatedPagesRemainUntouched() {
        val origin = ThorNavigation(tab = ThorTab.Library).push(ThorPage.Collection(ThorCollection.OwnedPlaylists))
        for (route in listOf(ThorAccountRoute.Login, ThorAccountRoute.MobileLogin, ThorAccountRoute.WebLogin, ThorAccountRoute.CookieLogin)) {
            assertEquals(origin, origin.push(account(route)).completeAccountLogin())
        }
        val ordinary = origin.push(ThorPage.AudioSettings).toggle(ThorOverlay.Player)
        assertEquals(ordinary, ordinary.completeAccountLogin())
    }

    @Test
    fun theQuickPlayerOverlayPreservesNativeAccountAndAudioOrigins() {
        val root = ThorNavigation(tab = ThorTab.My)
        for (origin in listOf(
            root.push(account(ThorAccountRoute.Web(OfficialNeteasePage.PhoneBinding))),
            root.push(ThorPage.AudioSettings).push(ThorPage.Equalizer),
        )) {
            val history = ThorQuickPlayerHistory()
            val player = history.enter(origin, menu = null, account = 7)
            assertEquals(origin.page, player.page)
            assertEquals(ThorOverlay.Player, player.overlay)
            assertEquals(origin, history.leave(player, account = 7)?.navigation)
            assertNull(history.leave(player, account = 7))
        }
    }

    @Test
    fun accountGenerationAndLoginCompletionAreOrderIndependent() {
        val privateDetail = ThorPage.Detail(ThorItem(ThorItemKey.Playlist("qa-private"), "QA private playlist", owned = true))
        val origins = listOf(
            myRoot(),
            ThorNavigation(tab = ThorTab.Library).push(ThorPage.Collection(ThorCollection.LikedSongs)),
            ThorNavigation(tab = ThorTab.Library).push(privateDetail),
            myRoot().push(account(ThorAccountRoute.Home)).push(account(ThorAccountRoute.Bindings)),
        )
        for (origin in origins) {
            val pending = origin.push(account(ThorAccountRoute.Login)).push(account(ThorAccountRoute.WebLogin))
            val generationFirst = pending.forChangedAccount().completeAccountLogin()
            val completionFirst = pending.completeAccountLogin().forChangedAccount()
            assertEquals("A session projection arriving first must not change the auth destination",
                completionFirst, generationFirst)
            assertFalse(generationFirst.stacks.values.flatten().any { it is ThorPage.Detail })
        }
    }

    @Test
    fun anAccountChangeDropsPrivateDetailAndWebContextsAcrossEveryTab() {
        val detail = ThorPage.Detail(ThorItem(ThorItemKey.Playlist("qa-private"), "QA private playlist", owned = true))
        val privateWeb = account(ThorAccountRoute.Web(OfficialNeteasePage.PhoneBinding))
        val connections = account(ThorAccountRoute.Connections(41, AccountConnectionType.Followers))
        val navigation = ThorNavigation(tab = ThorTab.Library, stacks = mapOf(
            ThorTab.Library to listOf(ThorPage.Root(ThorTab.Library), ThorPage.Collection(ThorCollection.OwnedPlaylists), detail),
            ThorTab.My to listOf(ThorPage.Root(ThorTab.My), account(ThorAccountRoute.Home), privateWeb, connections),
            ThorTab.Home to listOf(ThorPage.Root(ThorTab.Home), account(ThorAccountRoute.Login), account(ThorAccountRoute.WebLogin)),
        ))
        val changed = navigation.forChangedAccount()
        assertEquals(ThorTab.Library, changed.tab)
        assertEquals(ThorPage.Collection(ThorCollection.OwnedPlaylists), changed.page)
        assertEquals(listOf(ThorPage.Root(ThorTab.My), account(ThorAccountRoute.Home)), changed.stacks[ThorTab.My])
        assertEquals(navigation.stacks[ThorTab.Home], changed.stacks[ThorTab.Home])
        assertEquals(changed, changed.forChangedAccount())
    }

    private fun account(route: ThorAccountRoute) = ThorPage.Account(route)
    private fun myRoot() = ThorNavigation(tab = ThorTab.My,
        stacks = mapOf(ThorTab.My to listOf(ThorPage.Root(ThorTab.My))))
}
