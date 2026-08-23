package com.theovit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
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

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientThread clientThread;

	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, BankedCountConfig.GROUP);
	private static final Type CACHE_TYPE = new TypeToken<Map<Integer, Integer>>()
	{
	}.getType();

	// null = bank not opened yet this session; non-null (possibly empty) = bank observed
	private Map<Integer, Integer> bankQuantities = null;

	private boolean bankReminderShown = false;

	private ScheduledFuture<?> pendingSave;

	private Set<String> excludedItemNames = Collections.emptySet();

	private Map<String, OverlayCorner> positionOverrides = Collections.emptyMap();

	@Override
	protected void startUp() throws Exception
	{
		rebuildExcludedItems();
		rebuildPositionOverrides();
		overlayManager.add(overlay);
		log.debug("Inventory Banked Counter started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		bankQuantities = null;
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
			pendingSave = null;
		}
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
		else if (event.getGameState() == GameState.LOGGED_IN && !bankReminderShown)
		{
			if (config.persistBankCache())
			{
				loadCachedBankAsync(true);
			}
			else
			{
				showLoginNotice(false);
			}
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
		scheduleSave(quantities);
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

		Menu positionMenu = client.getMenu().createMenuEntry(-1)
			.setOption("Move banked count")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.createSubMenu();

		positionMenu.createMenuEntry(-1)
			.setOption("Default")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> setPositionOverride(name, null));

		for (OverlayCorner corner : OverlayCorner.values())
		{
			positionMenu.createMenuEntry(-1)
				.setOption(corner.toString())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setPositionOverride(name, corner));
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(BankedCountConfig.GROUP))
		{
			return;
		}

		rebuildExcludedItems();
		rebuildPositionOverrides();

		if ("persistBankCache".equals(event.getKey()) && config.persistBankCache())
		{
			loadCachedBankAsync(false);
		}
	}

	Integer getBankQuantity(int canonicalItemId)
	{
		return bankQuantities == null ? null : bankQuantities.get(canonicalItemId);
	}

	boolean isExcluded(String itemName)
	{
		return excludedItemNames.contains(itemName.toLowerCase());
	}

	OverlayCorner getPositionOverride(String itemName)
	{
		return positionOverrides.get(itemName.toLowerCase());
	}

	private void showLoginNotice(boolean usedCache)
	{
		if (bankReminderShown)
		{
			return;
		}
		bankReminderShown = true;

		String message;
		if (usedCache)
		{
			message = "<col=ff0000>Inventory Banked Counter:</col> showing banked counts saved from your last visit — these may be out of date. Open your bank to refresh.";
		}
		else if (config.persistBankCache())
		{
			message = "<col=ff0000>Inventory Banked Counter:</col> open your bank once to start showing banked item counts.";
		}
		else
		{
			message = "<col=ff0000>Inventory Banked Counter:</col> open your bank once to start showing banked item counts. Tip: enable \"Remember banked counts after logout\" in the settings to keep counts across logins.";
		}
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
	}

	private void loadCachedBankAsync(boolean announce)
	{
		clientThread.invoke(() ->
		{
			long hash = client.getAccountHash();
			executor.execute(() ->
			{
				Map<Integer, Integer> loaded = readCacheFile(hash);
				clientThread.invoke(() ->
				{
					boolean usedCache = loaded != null && bankQuantities == null;
					if (usedCache)
					{
						bankQuantities = loaded;
					}
					if (announce)
					{
						showLoginNotice(usedCache);
					}
				});
			});
		});
	}

	private void scheduleSave(Map<Integer, Integer> quantities)
	{
		if (!config.persistBankCache())
		{
			return;
		}

		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return;
		}

		if (pendingSave != null)
		{
			pendingSave.cancel(false);
		}
		pendingSave = executor.schedule(() -> writeCacheFile(hash, quantities), 2, TimeUnit.SECONDS);
	}

	private Map<Integer, Integer> readCacheFile(long accountHash)
	{
		if (accountHash == -1)
		{
			return null;
		}

		File file = new File(CACHE_DIR, accountHash + ".json");
		if (!file.isFile())
		{
			return null;
		}

		try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
		{
			return gson.fromJson(reader, CACHE_TYPE);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Failed to read banked-count cache", e);
			return null;
		}
	}

	private void writeCacheFile(long accountHash, Map<Integer, Integer> quantities)
	{
		try
		{
			CACHE_DIR.mkdirs();
			File file = new File(CACHE_DIR, accountHash + ".json");
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))
			{
				gson.toJson(quantities, CACHE_TYPE, writer);
			}
		}
		catch (IOException e)
		{
			log.debug("Failed to write banked-count cache", e);
		}
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

	private void rebuildPositionOverrides()
	{
		Map<String, OverlayCorner> overrides = new HashMap<>();
		for (String token : Text.fromCSV(config.itemPositionOverrides()))
		{
			int separator = token.indexOf(':');
			if (separator <= 0 || separator == token.length() - 1)
			{
				continue;
			}

			String name = token.substring(0, separator).trim().toLowerCase();
			String cornerName = token.substring(separator + 1).trim();
			try
			{
				overrides.put(name, OverlayCorner.valueOf(cornerName));
			}
			catch (IllegalArgumentException e)
			{
				log.debug("Invalid overlay position override: {}", token);
			}
		}
		positionOverrides = overrides;
	}

	private void setPositionOverride(String itemName, OverlayCorner corner)
	{
		List<String> tokens = new ArrayList<>();
		for (String token : Text.fromCSV(config.itemPositionOverrides()))
		{
			int separator = token.indexOf(':');
			String name = separator > 0 ? token.substring(0, separator).trim() : token;
			if (!name.equalsIgnoreCase(itemName))
			{
				tokens.add(token);
			}
		}

		if (corner != null)
		{
			tokens.add(itemName + ":" + corner.name());
		}

		configManager.setConfiguration(BankedCountConfig.GROUP, "itemPositionOverrides", Text.toCSV(tokens));

		String message = corner != null
			? "Moved " + itemName + "'s banked count to " + corner + "."
			: "Reset " + itemName + "'s banked count to the default position.";
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
	}

	@Provides
	BankedCountConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankedCountConfig.class);
	}
}
