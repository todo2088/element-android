/*
 * Copyright (c) 2022 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.list.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedList
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.airbnb.mvrx.UniqueOnly
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.epoxy.LayoutManagerStateRestorer
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.resources.UserPreferencesProvider
import im.vector.app.core.utils.FirstItemUpdatedObserver
import im.vector.app.databinding.FragmentRoomListBinding
import im.vector.app.features.analytics.plan.ViewRoom
import im.vector.app.features.home.room.list.RoomListAnimator
import im.vector.app.features.home.room.list.RoomListListener
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsBottomSheet
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedAction
import im.vector.app.features.home.room.list.actions.RoomListQuickActionsSharedActionViewModel
import im.vector.app.features.home.room.list.home.header.HomeRoomFilter
import im.vector.app.features.home.room.list.home.header.HomeRoomsHeadersController
import im.vector.app.features.home.room.list.home.invites.InvitesActivity
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class HomeRoomListFragment :
        VectorBaseFragment<FragmentRoomListBinding>(),
        RoomListListener {

    @Inject lateinit var userPreferencesProvider: UserPreferencesProvider
    @Inject lateinit var headersController: HomeRoomsHeadersController
    @Inject lateinit var roomsController: HomeFilteredRoomsController

    private val roomListViewModel: HomeRoomListViewModel by fragmentViewModel()
    private lateinit var sharedQuickActionsViewModel: RoomListQuickActionsSharedActionViewModel
    private var concatAdapter = ConcatAdapter()
    private lateinit var firstItemObserver: FirstItemUpdatedObserver
    private var modelBuildListener: OnModelBuildFinishedListener? = null

    private lateinit var stateRestorer: LayoutManagerStateRestorer
    private var roomsListLive: LiveData<PagedList<RoomSummary>>? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomListBinding {
        return FragmentRoomListBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.stateView.contentView = views.roomListView
        views.stateView.state = StateView.State.Loading
        setupObservers()
        setupRecyclerView()
    }

    override fun onStart() {
        super.onStart()

        // Local rooms should not exist anymore when the room list is shown
        roomListViewModel.handle(HomeRoomListAction.DeleteAllLocalRoom)
    }

    private fun setupObservers() {
        sharedQuickActionsViewModel = activityViewModelProvider[RoomListQuickActionsSharedActionViewModel::class.java]
        sharedQuickActionsViewModel
                .stream()
                .onEach(::handleQuickActions)
                .launchIn(viewLifecycleOwner.lifecycleScope)

        roomListViewModel.observeViewEvents {
            when (it) {
                is HomeRoomListViewEvents.Loading -> showLoading(it.message)
                is HomeRoomListViewEvents.Failure -> showFailure(it.throwable)
                is HomeRoomListViewEvents.SelectRoom -> handleSelectRoom(it, it.isInviteAlreadyAccepted)
                is HomeRoomListViewEvents.Done -> Unit
            }
        }
    }

    private fun handleQuickActions(quickAction: RoomListQuickActionsSharedAction) {
        when (quickAction) {
            is RoomListQuickActionsSharedAction.NotificationsAllNoisy -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES_NOISY))
            }
            is RoomListQuickActionsSharedAction.NotificationsAll -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.ALL_MESSAGES))
            }
            is RoomListQuickActionsSharedAction.NotificationsMentionsOnly -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MENTIONS_ONLY))
            }
            is RoomListQuickActionsSharedAction.NotificationsMute -> {
                roomListViewModel.handle(HomeRoomListAction.ChangeRoomNotificationState(quickAction.roomId, RoomNotificationState.MUTE))
            }
            is RoomListQuickActionsSharedAction.Settings -> {
                navigator.openRoomProfile(requireActivity(), quickAction.roomId)
            }
            is RoomListQuickActionsSharedAction.Favorite -> {
                roomListViewModel.handle(HomeRoomListAction.ToggleTag(quickAction.roomId, RoomTag.ROOM_TAG_FAVOURITE))
            }
            is RoomListQuickActionsSharedAction.LowPriority -> {
                roomListViewModel.handle(HomeRoomListAction.ToggleTag(quickAction.roomId, RoomTag.ROOM_TAG_LOW_PRIORITY))
            }
            is RoomListQuickActionsSharedAction.Leave -> {
                promptLeaveRoom(quickAction.roomId)
            }
        }
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(context)
        firstItemObserver = FirstItemUpdatedObserver(layoutManager) {
            layoutManager.scrollToPosition(0)
        }
        stateRestorer = LayoutManagerStateRestorer(layoutManager).register()
        views.roomListView.layoutManager = layoutManager
        views.roomListView.itemAnimator = RoomListAnimator()
        layoutManager.recycleChildrenOnDetach = true

        modelBuildListener = OnModelBuildFinishedListener { it.dispatchTo(stateRestorer) }

        roomListViewModel.onEach(HomeRoomListViewState::headersData) {
            headersController.submitData(it)
        }

        roomListViewModel.roomsLivePagedList.observe(viewLifecycleOwner) { roomsList ->
            roomsController.submitList(roomsList)
            if (roomsList.isEmpty()) {
                roomsController.requestForcedModelBuild()
            }
        }

        roomListViewModel.emptyStateFlow.onEach { emptyStateOptional ->
            roomsController.submitEmptyStateData(emptyStateOptional.getOrNull())
        }.launchIn(lifecycleScope)

        setUpAdapters()

        views.roomListView.adapter = concatAdapter

        concatAdapter.registerAdapterDataObserver(firstItemObserver)
    }

    override fun invalidate() = withState(roomListViewModel) { state ->
        views.stateView.state = state.state
    }

    private fun setUpAdapters() {
        val headersAdapter = headersController.also { controller ->
            controller.invitesClickListener = ::onInvitesCounterClicked
            controller.onFilterChangedListener = ::onRoomFilterChanged
            controller.recentsRoomListener = this
        }.adapter

        val roomsAdapter = roomsController
                .also { controller ->
                    controller.listener = this
                }.adapter

        concatAdapter.addAdapter(headersAdapter)
        concatAdapter.addAdapter(roomsAdapter)
    }

    private fun promptLeaveRoom(roomId: String) {
        val isPublicRoom = roomListViewModel.isPublicRoom(roomId)
        val message = buildString {
            append(getString(R.string.room_participants_leave_prompt_msg))
            if (!isPublicRoom) {
                append("\n\n")
                append(getString(R.string.room_participants_leave_private_warning))
            }
        }
        MaterialAlertDialogBuilder(requireContext(), if (isPublicRoom) 0 else R.style.ThemeOverlay_Vector_MaterialAlertDialog_Destructive)
                .setTitle(R.string.room_participants_leave_prompt_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_leave) { _, _ ->
                    roomListViewModel.handle(HomeRoomListAction.LeaveRoom(roomId))
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    private fun onInvitesCounterClicked() {
        startActivity(Intent(activity, InvitesActivity::class.java))
    }

    private fun onRoomFilterChanged(filter: HomeRoomFilter) {
        roomListViewModel.handle(HomeRoomListAction.ChangeRoomFilter(filter))
    }

    private fun handleSelectRoom(event: HomeRoomListViewEvents.SelectRoom, isInviteAlreadyAccepted: Boolean) {
        navigator.openRoom(
                context = requireActivity(),
                roomId = event.roomSummary.roomId,
                isInviteAlreadyAccepted = isInviteAlreadyAccepted,
                trigger = ViewRoom.Trigger.RoomList
        )
    }

    override fun onDestroyView() {
        views.roomListView.cleanup()

        headersController.recentsRoomListener = null
        headersController.invitesClickListener = null
        headersController.onFilterChangedListener = null

        roomsController.listener = null

        concatAdapter.unregisterAdapterDataObserver(firstItemObserver)

        super.onDestroyView()
    }

    // region RoomListListener

    override fun onRoomClicked(room: RoomSummary) {
        roomListViewModel.handle(HomeRoomListAction.SelectRoom(room))
    }

    override fun onRoomLongClicked(room: RoomSummary): Boolean {
        userPreferencesProvider.neverShowLongClickOnRoomHelpAgain()
        RoomListQuickActionsBottomSheet
                .newInstance(room.roomId)
                .show(childFragmentManager, "ROOM_LIST_QUICK_ACTIONS")
        return true
    }

    override fun onRejectRoomInvitation(room: RoomSummary) = Unit

    override fun onAcceptRoomInvitation(room: RoomSummary) = Unit

    override fun onJoinSuggestedRoom(room: SpaceChildInfo) = Unit

    override fun onSuggestedRoomClicked(room: SpaceChildInfo) = Unit

    // endregion
}
