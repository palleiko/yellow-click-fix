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

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the client's own red click cross for rescued clicks. client.menuAction passes no click
 * position, so the cross the client draws for those clicks lands off screen.
 */
class YellowClickFixOverlay extends Overlay
{
	// The client holds 8 cross frames: 0-3 yellow, 4-7 red
	private static final int RED_FRAME_OFFSET = 4;
	private static final int FRAMES = 4;
	// The client steps the cross 20 units per cycle and shows frame units / 100
	private static final int CYCLES_PER_FRAME = 5;
	// The client draws the cross sprite at the click position minus 8
	private static final int DRAW_OFFSET = 8;

	private final Client client;

	private boolean active;
	private int canvasX;
	private int canvasY;
	private int startCycle;

	@Inject
	YellowClickFixOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
	}

	void show(int canvasX, int canvasY)
	{
		this.canvasX = canvasX;
		this.canvasY = canvasY;
		startCycle = client.getGameCycle();
		active = true;
	}

	void clear()
	{
		active = false;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!active)
		{
			return null;
		}

		int frame = (client.getGameCycle() - startCycle) / CYCLES_PER_FRAME;
		SpritePixels[] sprites = client.getCrossSprites();
		if (frame < 0 || frame >= FRAMES || sprites == null
			|| sprites.length < RED_FRAME_OFFSET + FRAMES)
		{
			active = false;
			return null;
		}

		SpritePixels sprite = sprites[RED_FRAME_OFFSET + frame];
		graphics.drawImage(sprite.toBufferedImage(),
			canvasX - DRAW_OFFSET + sprite.getOffsetX(),
			canvasY - DRAW_OFFSET + sprite.getOffsetY(), null);
		return null;
	}
}
