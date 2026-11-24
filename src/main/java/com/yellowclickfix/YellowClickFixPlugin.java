/*
 * Copyright (c) 2026, Ian Palleiko <ian@ian.re>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.yellowclickfix;

import com.google.inject.Provides;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects and optionally rescues left clicks that the client turned into "Walk here" even though
 * the click landed within an NPC's clickbox.
 *
 * This happens because the list of scene entities under the cursor is only produced while rendering
 * a frame from the cursor position sampled at the start of the previous client cycle. A click that
 * arrives less than one cycle after the cursor reached the NPC is dispatched against that stale
 * list, so the menu holds no entries for the NPC at all and the top entry is still "Walk here".
 *
 * A click is only treated as stale when the click-time menu has no entry for any NPC under the
 * click. If the NPC is in the menu and "Walk here" is still on top, the click is left alone.
 *
 * Both event handlers run on the client thread.
 */
@PluginDescriptor(
	name = "Yellow Click Fix",
	description = "Detects left clicks on NPCs that were incorrectly dispatched as Walk here",
	tags = {"click", "walk", "menu", "npc", "stale", "yellow"}
)
public class YellowClickFixPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(YellowClickFixPlugin.class);

	@Inject
	private Client client;

	@Inject
	private YellowClickFixConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private YellowClickFixOverlay overlay;

	// A stale walk seen in the current cycle, resolved on the next cycle's PostMenuSort. Holds
	// every NPC whose clickbox contains the click, since NPCs are often stacked.
	private List<NPC> pendingNpcs = Collections.emptyList();
	private int pendingP0;
	private int pendingP1;
	private int pendingCycle;
	private boolean pendingHeld;

	// True while this plugin is calling client.menuAction, so its own clicks are ignored
	private boolean replaying;

	private int walkClicks;
	private int staleClicks;

	@Provides
	YellowClickFixConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YellowClickFixConfig.class);
	}

	@Override
	protected void startUp()
	{
		walkClicks = 0;
		staleClicks = 0;
		pendingNpcs = Collections.emptyList();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		pendingNpcs = Collections.emptyList();
		overlay.clear();
		overlayManager.remove(overlay);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// isMenuOpen() is still true while a right-click menu selection is being dispatched, so
		// this only lets direct left clicks through
		if (replaying || event.isConsumed() || client.isMenuOpen()
			|| event.getMenuAction() != MenuAction.WALK)
		{
			return;
		}

		walkClicks++;

		// Recover the viewport relative click from the walk event
		int clickX = event.getParam0() + client.getViewportXOffset();
		int clickY = event.getParam1() + client.getViewportYOffset();
		List<NPC> npcs = findNpcsAt(clickX, clickY);
		if (npcs.isEmpty())
		{
			return;
		}

		// The menu still holds the entries this click was dispatched against. If it already has an
		// entry for an NPC under the click, the pick list was not stale and "Walk here" is on top
		// because a swapper or the client put it there. Leave that click alone.
		MenuEntry[] clickMenu = client.getMenu().getMenuEntries();
		for (MenuEntry entry : clickMenu)
		{
			if (refersToAny(entry, npcs))
			{
				log.debug("[YellowClickFix] walk at ({},{}) left alone, menu already had '{} {}'",
					clickX, clickY, entry.getOption(), entry.getTarget());
				return;
			}
		}

		staleClicks++;
		pendingNpcs = npcs;
		pendingP0 = event.getParam0();
		pendingP1 = event.getParam1();
		pendingCycle = client.getGameCycle();
		pendingHeld = config.rescueClicks();
		if (pendingHeld)
		{
			// Consume the walk as it is replaced or re-issued on the next cycle
			event.consume();
		}
	}

	// Lowest priority so every menu swapper has finished reordering before the top entry is read.
	// Swappers subscribe to this event at priority 0 or slightly below it.
	@Subscribe(priority = -Float.MAX_VALUE)
	public void onPostMenuSort(PostMenuSort event)
	{
		// PostMenuSort fires before click dispatch, so the one in the click's own cycle has already
		// passed by the time a pending click is recorded. The next one is one cycle later, when the
		// menu has been rebuilt from a frame rendered after the click.
		if (pendingNpcs.isEmpty() || client.getGameCycle() == pendingCycle)
		{
			return;
		}

		List<NPC> npcs = pendingNpcs;
		boolean held = pendingHeld;
		int age = client.getGameCycle() - pendingCycle;
		pendingNpcs = Collections.emptyList();

		// The last entry in the list is the top / left click option
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		MenuEntry top = entries.length > 0 ? entries[entries.length - 1] : null;

		// Only attempt to rescue the click action if the click was on an NPC
		boolean topIsNpc = top != null
			&& isNpcOption(top.getType())
			&& !top.isDeprioritized()
			&& top.getWorldViewId() == WorldView.TOPLEVEL
			&& refersToAny(top, npcs);

		String topText = top == null ? "nothing" : describe(top);
		String outcome;
		if (!held)
		{
			outcome = topIsNpc ? "not rescued (rescue is off), would have fired " + topText
				: "not rescued (rescue is off), next-cycle top entry was " + topText;
		}
		else if (topIsNpc)
		{
			replay(top.getParam0(), top.getParam1(), top.getType(), top.getIdentifier(),
				top.getItemId(), top.getOption(), top.getTarget());
			// Red cross at the original click position
			overlay.show(pendingP0 + client.getViewportXOffset(),
				pendingP1 + client.getViewportYOffset());
			outcome = "rescued, fired " + topText;
		}
		else
		{
			// The cursor left the NPC, or a swapper wants the walk
			replay(pendingP0, pendingP1, MenuAction.WALK, 0, 0, "Walk here", "");
			outcome = "walk re-issued, next-cycle top entry was " + topText;
		}

		NPC first = npcs.get(0);
		Point mouse = client.getMouseCanvasPosition();
		String message = String.format(
			"stale walk %d of %d walk clicks: click (%d,%d) was on %s [%d]%s, cursor now (%d,%d),"
				+ " resolved after %d cycle(s): %s",
			staleClicks, walkClicks,
			pendingP0 + client.getViewportXOffset(), pendingP1 + client.getViewportYOffset(),
			first.getName(), first.getIndex(),
			npcs.size() > 1 ? " and " + (npcs.size() - 1) + " more" : "",
			mouse.getX(), mouse.getY(), age, outcome);
		log.info("[YellowClickFix] {}", message);
		if (config.chatFeedback())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[YellowClickFix] " + message, null);
		}
	}

	private void replay(int param0, int param1, MenuAction action, int id, int itemId,
		String option, String target)
	{
		replaying = true;
		try
		{
			client.menuAction(param0, param1, action, id, itemId, option, target);
		}
		finally
		{
			replaying = false;
		}
	}

	/**
	 * Finds the NPCs whose clickbox contains the point. Perspective.getClickbox rebuilds the shape
	 * the client's own hover test uses, so this matches what the client would have picked had it
	 * tested the click position. It's only used as a check to determine if the click was on a
	 * stale menu entry; which entry actually fires is decided by the client's own menu one client
	 * cycle later.
	 */
	private List<NPC> findNpcsAt(int canvasX, int canvasY)
	{
		List<NPC> found = new ArrayList<>();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !hasLeftClickOption(npc))
			{
				continue;
			}

			Shape clickbox = getClickbox(npc);
			if (clickbox != null && clickbox.contains(canvasX, canvasY))
			{
				found.add(npc);
			}
		}
		return found;
	}

	private Shape getClickbox(NPC npc)
	{
		Model model = npc.getModel();
		LocalPoint location = npc.getLocalLocation();
		WorldView worldView = npc.getWorldView();
		if (model == null || location == null || worldView == null)
		{
			return null;
		}

		// Model height computed the same way as RuneLite's Actor.getConvexHull()
		int z = Perspective.getFootprintTileHeight(client, location, worldView.getPlane(),
			npc.getFootprintSize()) - npc.getAnimationHeightOffset();
		return Perspective.getClickbox(client, worldView, model, npc.getCurrentOrientation(),
			location.getX(), location.getY(), z);
	}

	private static boolean hasLeftClickOption(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null || !composition.isInteractible())
		{
			return false;
		}

		String[] actions = composition.getActions();
		if (actions == null)
		{
			return false;
		}

		for (String action : actions)
		{
			if (action != null && !action.isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True if the entry targets one of the NPCs. Matches on the entry's NPC, and also on type plus
	 * NPC index so examine and use-on entries count even if the entry does not expose its NPC.
	 */
	private static boolean refersToAny(MenuEntry entry, List<NPC> npcs)
	{
		NPC entryNpc = entry.getNpc();
		MenuAction type = entry.getType();
		boolean npcType = isNpcOption(type) || type == MenuAction.EXAMINE_NPC
			|| type == MenuAction.WIDGET_TARGET_ON_NPC;
		for (NPC npc : npcs)
		{
			if (entryNpc == npc)
			{
				return true;
			}
			if (npcType && entry.getWorldViewId() == WorldView.TOPLEVEL
				&& entry.getIdentifier() == npc.getIndex())
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isNpcOption(MenuAction action)
	{
		switch (action)
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private static String describe(MenuEntry entry)
	{
		return "'" + entry.getOption() + " " + entry.getTarget() + "' (" + entry.getType() + ")";
	}
}

