package com.theovit;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(BankedCountConfig.GROUP)
public interface BankedCountConfig extends Config
{
	String GROUP = "inventory-banked-count";

	@ConfigItem(
		keyName = "excludedItems",
		name = "Excluded items",
		description = "Comma-separated list of item names to hide from the banked-count overlay (case-insensitive)."
	)
	default String excludedItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "shorthandNumbers",
		name = "Shorthand numbers",
		description = "Abbreviate large banked counts, e.g. 1.2K, 1.2M, 1.2B, instead of showing the full number."
	)
	default boolean shorthandNumbers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "textColor",
		name = "Overlay text color",
		description = "Color of the banked-count text drawn on inventory items."
	)
	default Color textColor()
	{
		return Color.CYAN;
	}

	@ConfigItem(
		keyName = "fontSize",
		name = "Overlay font size",
		description = "Font size of the banked-count text drawn on inventory items."
	)
	@Range(min = 8, max = 24)
	default int fontSize()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "overlayPosition",
		name = "Overlay position",
		description = "Corner of the item slot to draw the banked count in — useful if another plugin already draws an overlay in the default corner."
	)
	default OverlayCorner overlayPosition()
	{
		return OverlayCorner.BOTTOM_RIGHT;
	}

	@ConfigItem(
		keyName = "itemPositionOverrides",
		name = "Per-item overlay position",
		description = "Overrides the overlay position for specific items, e.g. Cake:TOP_LEFT. Normally managed via shift-right-click > Move banked count on an item."
	)
	default String itemPositionOverrides()
	{
		return "";
	}

	@ConfigItem(
		keyName = "persistBankCache",
		name = "Remember banked counts after logout",
		description = "Keeps your last known banked counts visible after logging out, without needing to reopen your bank first. Numbers may be inaccurate until you next visit your bank — stored locally, per account.",
		warning = "Banked counts may be inaccurate when shown from this cache:\n"
			+ "- You may have visited your bank on another device or client since it was last saved\n"
			+ "- This is a snapshot from your last bank visit, not live data — it won't reflect anything that changed while you were logged out\n\n"
			+ "Only enable this if occasional stale numbers are acceptable until you reopen your bank."
	)
	default boolean persistBankCache()
	{
		return false;
	}
}
