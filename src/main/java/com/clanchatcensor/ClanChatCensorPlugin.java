package com.clanchatcensor;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import net.runelite.api.Point;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Replaces chat messages from a list of players with "Blocked message" and lets the
 * user click the replacement to reveal and  re-hide the original text.
 *  - When a message from a blocked player arrives we keep the original text and overwrite
 *    the MessageNode's value with the replacement text.
 *  - The replacement is prefixed with an empty colour tag, {@code <col=01XXXX></col>}, that encodes
 *    which message it is. It renders as nothing, but survives into the chatbox line widget's text,
 *    so a click can be mapped back to the message that was clicked.
 *  - Revealing/hiding just swaps the node's value and asks the client to rebuild the chat.
 */
@PluginDescriptor(
		name = "Clan Chat Censor",
		description = "Replaces messages from chosen players with \"Blocked message\" that you can click to reveal",
		tags = {"clan", "chat", "block", "ignore", "censor", "filter", "friends"}
)
public class ClanChatCensorPlugin extends Plugin
{
	/** Interface group id of the chatbox. */
	private static final int CHATBOX_GROUP_ID = 162;
	/** Upper bound on component ids scanned inside the chatbox group when looking for a clicked line. */
	private static final int MAX_CHATBOX_COMPONENTS = 1024;
	private static final int MAX_TRACKED_MESSAGES = 500;
	private static final String DEFAULT_CENSOR_TEXT = "Blocked message";

	/** Matches the hidden marker. The 01 prefix keeps it from being confused with real colour tags. */
	private static final Pattern MARKER = Pattern.compile("<col=01([0-9a-fA-F]{4})>");
	private static final Pattern NAME_SPLITTER = Pattern.compile("[,\\r\\n]+");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClanChatCensorConfig config;

	@Inject
	private MouseManager mouseManager;

	private volatile Set<String> blockedNames = Collections.emptySet();

	/** Censored messages, keyed by the low 16 bits of their message id. Only touched on the client thread. */
	private final Map<Integer, BlockedMessage> tracked = new LinkedHashMap<Integer, BlockedMessage>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, BlockedMessage> eldest)
		{
			return size() > MAX_TRACKED_MESSAGES;
		}
	};

	private final MouseListener mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (SwingUtilities.isLeftMouseButton(e))
			{
				clientThread.invokeLater(ClanChatCensorPlugin.this::handleClick);
			}
			return e;
		}
	};

	@Provides
	ClanChatCensorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanChatCensorConfig.class);
	}

	@Override
	protected void startUp()
	{
		parseBlockedNames();
		mouseManager.registerMouseListener(mouseListener);
		clientThread.invokeLater(this::rescan);
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(mouseListener);
		clientThread.invokeLater(() ->
		{
			for (BlockedMessage blocked : tracked.values())
			{
				blocked.node.setValue(blocked.original);
			}
			tracked.clear();
			refreshChat();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			tracked.clear();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!blockedNames.isEmpty())
		{
			censor(event.getMessageNode());
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ClanChatCensorConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			parseBlockedNames();
			rescan();
		});
	}

	// ---------------------------------------------------------------------------------------------
	// Censoring
	// ---------------------------------------------------------------------------------------------

	/**
	 * Censors the node if it is from a blocked player in an enabled chat type.
	 * @return true if the node was newly censored
	 */
	private boolean censor(MessageNode node)
	{
		if (node == null || !isEnabledType(node.getType()))
		{
			return false;
		}

		final String sender = normalise(node.getName());
		if (!blockedNames.contains(sender))
		{
			return false;
		}

		final int key = keyOf(node);
		final BlockedMessage existing = tracked.get(key);
		if (existing != null && existing.node == node)
		{
			return false;
		}

		final String value = node.getValue();
		if (value == null)
		{
			return false;
		}

		final BlockedMessage blocked = new BlockedMessage(node, sender, value);
		tracked.put(key, blocked);
		render(blocked);
		return true;
	}

	/** Writes the message's current state censored or revealed into its node. */
	private void render(BlockedMessage blocked)
	{
		final String content = blocked.revealed ? blocked.content : censoredText();
		blocked.node.setValue(blocked.prefix + marker(blocked.node) + content);
	}

	/**
	 * Re-applies settings to everything already in chat: un-censors messages that no longer qualify,
	 * refreshes the replacement text, and censors any older messages from newly blocked players.
	 */
	private void rescan()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		for (Iterator<BlockedMessage> it = tracked.values().iterator(); it.hasNext(); )
		{
			final BlockedMessage blocked = it.next();
			if (isEnabledType(blocked.node.getType()) && blockedNames.contains(blocked.sender))
			{
				render(blocked);
			}
			else
			{
				blocked.node.setValue(blocked.original);
				it.remove();
			}
		}

		if (!blockedNames.isEmpty())
		{
			final IterableHashTable<MessageNode> messages = client.getMessages();
			if (messages != null)
			{
				for (MessageNode node : messages)
				{
					censor(node);
				}
			}
		}

		refreshChat();
	}

	private void refreshChat()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			client.refreshChat();
		}
	}

	private String censoredText()
	{
		String text = config.censorText();
		if (text == null || text.trim().isEmpty())
		{
			text = DEFAULT_CENSOR_TEXT;
		}

		final Color color = config.censorColor();
		return color == null ? text : ColorUtil.wrapWithColorTag(text, color);
	}

	private boolean isEnabledType(ChatMessageType type)
	{
		if (type == null)
		{
			return false;
		}

		switch (type)
		{
			case CLAN_CHAT:
				return config.clanChat();
			case CLAN_GUEST_CHAT:
				return config.guestClanChat();
			case FRIENDSCHAT:
				return config.friendsChat();
			case CLAN_GIM_CHAT:
				return config.groupIronChat();
			case PUBLICCHAT:
				return config.publicChat();
			default:
				return false;
		}
	}

	private void parseBlockedNames()
	{
		final Set<String> names = new HashSet<>();
		for (String raw : NAME_SPLITTER.split(config.blockedPlayers()))
		{
			final String name = normalise(raw);
			if (!name.isEmpty())
			{
				names.add(name);
			}
		}
		blockedNames = names;
	}

	private static String normalise(String name)
	{
		if (name == null)
		{
			return "";
		}

		return Text.removeTags(name)
				.replace('\u00A0', ' ')
				.replace('_', ' ')
				.replace('-', ' ')
				.trim()
				.toLowerCase(Locale.ROOT);
	}

	// ---------------------------------------------------------------------------------------------
	// Clicking
	// ---------------------------------------------------------------------------------------------

	private void handleClick()
	{
		if (tracked.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return;
		}

		final int key = findMessageKeyAt(mouse.getX(), mouse.getY());
		if (key < 0)
		{
			return;
		}

		final BlockedMessage blocked = tracked.get(key);
		if (blocked == null || (blocked.revealed && !config.clickToHide()))
		{
			return;
		}

		blocked.revealed = !blocked.revealed;
		render(blocked);
		refreshChat();
	}

	private int findMessageKeyAt(int x, int y)
	{
		for (int child = 0; child < MAX_CHATBOX_COMPONENTS; child++)
		{
			final Widget container = client.getWidget(CHATBOX_GROUP_ID, child);
			if (container == null)
			{
				continue;
			}

			int key = keyAt(container, x, y);
			if (key >= 0)
			{
				return key;
			}

			final Widget[] lines = container.getDynamicChildren();
			if (lines == null)
			{
				continue;
			}

			for (Widget line : lines)
			{
				key = keyAt(line, x, y);
				if (key >= 0)
				{
					return key;
				}
			}
		}

		return -1;
	}

	/** Returns the tracked message key if this widget is a visible, tracked line containing the point, else -1. */
	private int keyAt(Widget widget, int x, int y)
	{
		if (widget == null)
		{
			return -1;
		}

		final String text = widget.getText();
		if (text == null || text.isEmpty())
		{
			return -1;
		}

		final Matcher matcher = MARKER.matcher(text);
		if (!matcher.find())
		{
			return -1;
		}

		final int key = Integer.parseInt(matcher.group(1), 16);
		if (!tracked.containsKey(key) || widget.isHidden())
		{
			return -1;
		}

		final Rectangle bounds = widget.getBounds();
		if (bounds == null || !bounds.contains(x, y))
		{
			return -1;
		}

		for (Widget parent = widget.getParent(); parent != null; parent = parent.getParent())
		{
			if (parent.getScrollHeight() > 0 || parent.getScrollWidth() > 0)
			{
				final Rectangle viewport = parent.getBounds();
				if (viewport != null && !viewport.contains(x, y))
				{
					return -1;
				}
			}
		}

		return key;
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private static int keyOf(MessageNode node)
	{
		return node.getId() & 0xFFFF;
	}

	/** An empty colour tag: invisible, but readable from the chatbox lines widget text. */
	private static String marker(MessageNode node)
	{
		return String.format("<col=01%04x></col>", keyOf(node));
	}

	private static final class BlockedMessage
	{
		private final MessageNode node;
		private final String sender;
		private final String original;
		private final String prefix;
		private final String content;
		private boolean revealed;

		private BlockedMessage(MessageNode node, String sender, String original)
		{
			this.node = node;
			this.sender = sender;
			this.original = original;

			final boolean gimPipe = node.getType() == ChatMessageType.CLAN_GIM_CHAT && original.startsWith("|");
			this.prefix = gimPipe ? "|" : "";
			this.content = gimPipe ? original.substring(1) : original;
		}
	}
}
