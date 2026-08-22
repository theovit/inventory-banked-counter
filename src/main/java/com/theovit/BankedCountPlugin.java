package com.theovit;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Inventory Banked Counter"
)
public class BankedCountPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private BankedCountConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BankedCountOverlay overlay;

	@Inject
	private ConfigManager configManager;

	// null = bank not opened yet this session; non-null (possibly empty) = bank observed
	private Map<Integer, Integer> bankQuantities = null;

	private boolean bankReminderShown = false;

	private Set<String> excludedItemNames = Collections.emptySet();

	@Override
	protected void startUp() throws Exception
	{
		rebuildExcludedItems();
		overlayManager.add(overlay);
		log.debug("Inventory Banked Counter started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		bankQuantities = null;
		log.debug("Inventory Banked Counter stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			bankQuantities = null;
			bankReminderShown = false;
		}
		else if (event.getGameState() == GameState.LOGGED_IN && !bankReminderShown && bankQuantities == null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>Inventory Banked Counter:</col> open your bank once to start showing banked item counts.", null);
			bankReminderShown = true;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.BANK)
		{
			return;
		}

		Map<Integer, Integer> quantities = new HashMap<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}

			ItemComposition composition = itemManager.getItemComposition(item.getId());
			if (composition.getPlaceholderTemplateId() != -1)
			{
				// placeholders occupy a bank slot with quantity 1 but aren't actually held
				continue;
			}

			int canonicalId = itemManager.canonicalize(item.getId());
			quantities.merge(canonicalId, item.getQuantity(), Integer::sum);
		}

		bankQuantities = quantities;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!"Examine".equals(event.getOption()) || !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		MenuEntry entry = event.getMenuEntry();
		Widget widget = entry.getWidget();
		if (widget == null || WidgetUtil.componentToInterface(widget.getId()) != InterfaceID.INVENTORY)
		{
			return;
		}

		int itemId = entry.getItemId();
		if (itemId <= 0)
		{
			return;
		}

		String name = itemManager.getItemComposition(itemManager.canonicalize(itemId)).getName();
		String option = isExcluded(name) ? "Include in banked count" : "Exclude from banked count";

		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> toggleExclusion(name));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(BankedCountConfig.GROUP))
		{
			return;
		}

		rebuildExcludedItems();
	}

	Integer getBankQuantity(int canonicalItemId)
	{
		return bankQuantities == null ? null : bankQuantities.get(canonicalItemId);
	}

	boolean isExcluded(String itemName)
	{
		return excludedItemNames.contains(itemName.toLowerCase());
	}

	private void rebuildExcludedItems()
	{
		Set<String> names = new HashSet<>();
		for (String name : Text.fromCSV(config.excludedItems()))
		{
			names.add(name.toLowerCase());
		}
		excludedItemNames = names;
	}

	private void toggleExclusion(String itemName)
	{
		List<String> names = new ArrayList<>(Text.fromCSV(config.excludedItems()));

		if (names.removeIf(n -> n.equalsIgnoreCase(itemName)))
		{
			configManager.setConfiguration(BankedCountConfig.GROUP, "excludedItems", Text.toCSV(names));
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Included " + itemName + " in the banked count overlay again.", null);
		}
		else
		{
			names.add(itemName);
			configManager.setConfiguration(BankedCountConfig.GROUP, "excludedItems", Text.toCSV(names));
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Excluded " + itemName + " from the banked count overlay.", null);
		}
	}

	@Provides
	BankedCountConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankedCountConfig.class);
	}
}
