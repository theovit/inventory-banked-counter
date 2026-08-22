package com.moon;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

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
}
