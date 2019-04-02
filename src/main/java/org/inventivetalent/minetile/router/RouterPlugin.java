package org.inventivetalent.minetile.router;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import org.inventivetalent.minetile.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import static net.md_5.bungee.api.event.ServerConnectEvent.Reason.JOIN_PROXY;

public class RouterPlugin extends Plugin implements Listener {

	Redisson redisson;

	public int tileSize       = 16;
	public int tileSizeBlocks = 256;

	public int startTileX = 0;
	public int startTileZ = 0;

	RMap<UUID, TileData>       tileMap;
	RMap<UUID, PlayerLocation> positionMap;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		Configuration config;
		try {
			config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(new File(getDataFolder(), "config.yml"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		getProxy().registerChannel("minetile:minetile");

		/// Redis
		Config redisConfig = new Config();
		String address = config.getString("redis.host") + ":" + config.getInt("redis.port");
		SingleServerConfig singleServerConfig = redisConfig
				.setCodec(new RedissonGsonCodec())
				.useSingleServer()
				.setAddress("redis://" + address);
		if (config.getString("redis.password", "").length() > 0) {
			singleServerConfig.setPassword(config.getString("redis.password"));
		} else {
			getLogger().warning("No password set for redis");
		}

		redisson = (Redisson) Redisson.create(redisConfig);
		getLogger().info("Connected to Redis @ " + address);

		startTileX = config.getInt("startTile.x", 0);
		startTileZ = config.getInt("startTile.z", 0);

		RMap<String, Object> settingsMap = redisson.getMap("MineTile:Settings");
		getLogger().info("Adding settings to redis...");
		for (String s : config.getSection("defaults").getKeys()) {
			settingsMap.put(s, config.get("defaults." + s));
		}

		tileSize = (int) settingsMap.getOrDefault("tileSize", 16);
		tileSizeBlocks = tileSize * 16;

		RTopic serverTopic = redisson.getTopic("MineTile:ServerDiscovery");
		serverTopic.addListener(ServerData.class, (channel, serverData) -> {
			System.out.println(channel);
			System.out.println(serverData);

			ServerInfo serverInfo = getProxy().constructServerInfo(serverData.serverId.toString(), new InetSocketAddress(serverData.host, serverData.port), "MineTile Server #" + serverData.serverId, false);
			getProxy().getServers().put(serverData.serverId.toString(), serverInfo);
			getLogger().info("Added Server " + serverData.serverId + " at " + serverData.host + ":" + serverData.port);
		});

		tileMap = redisson.getMap("MineTile:Tiles");
		positionMap = redisson.getMap("MineTile:Positions");

		RTopic teleportTopic = redisson.getTopic("MineTile:Teleports");
		teleportTopic.addListener(TeleportRequest.class, (channel, teleportRequest) -> {
			System.out.println(teleportRequest);

			routeToServerForLocation(teleportRequest);
		});

		RTopic controlTopic = redisson.getTopic("MineTile:ServerControl");
		getProxy().getPluginManager().registerCommand(this, new Command("restart-all-tiles", "minetile.globalrestart") {
			@Override
			public void execute(CommandSender sender, String[] args) {
				if (args.length == 0 || !"confirm".equalsIgnoreCase(args[0])) {
					sender.sendMessage(new ComponentBuilder("This will attempt to restart all of the registered tile-servers.\n")
							.append("Type '/restart-all-tiles confirm' to continue.").color(ChatColor.RED).create());
					return;
				}
				controlTopic.publish(new ControlRequest(ControlAction.RESTART));
				sender.sendMessage(new TextComponent("Restart request sent."));
			}
		});
		getProxy().getPluginManager().registerCommand(this, new Command("shutdown-all-tiles", "minetile.globalshutdown") {
			@Override
			public void execute(CommandSender sender, String[] args) {
				if (args.length == 0 || !"confirm".equalsIgnoreCase(args[0])) {
					sender.sendMessage(new ComponentBuilder("This will attempt to shut down all of the registered tile-servers.\n")
							.append("Type '/shutdown-all-tiles confirm' to continue.").color(ChatColor.RED).create());
					return;
				}
				controlTopic.publish(new ControlRequest(ControlAction.SHUTDOWN));
				sender.sendMessage(new TextComponent("Shutdown request sent."));
			}
		});

		// Rediscover running servers
		rediscover();
	}

	void rediscover() {
		tileMap.clear();

		RTopic controlTopic = redisson.getTopic("MineTile:ServerControl");
		controlTopic.publish(new ControlRequest(ControlAction.REDISCOVER));
	}

	public void routeToServerForLocation(TeleportRequest teleportRequest) {
		final Set<UUID> possibleServers = new HashSet<>();
		tileMap.forEach((k, v) -> {
			if (k.equals(teleportRequest.currentServer)) { return; }

			//				int minX = v.x - 1;
			//				int maxX = v.x + 1;
			//				int minZ = v.z - 1;
			//				int maxZ = v.z + 1;

			//				System.out.println("minX: " + minX);
			//				System.out.println("maxX: " + maxX);
			//				System.out.println("minZ: " + minZ);
			//				System.out.println("maxZ: " + maxZ);

			System.out.println("x: " + v.x);
			System.out.println("z: " + v.z);

			int tX = (int) Math.round(teleportRequest.x / (double) (tileSize * 2));
			int tZ = (int) Math.round(teleportRequest.z / (double) (tileSize * 2));

			System.out.println("tX: " + tX);
			System.out.println("tZ: " + tZ);

			//				if (tX >= minX && tX <= maxX && tZ >= minZ && tZ <= maxZ) {
			//					possibleServer[0] = k;
			//				}
			if (tX == v.x && tZ == v.z) {
				possibleServers.add(k);
			}
		});

		if (!possibleServers.isEmpty()) {
			boolean sent = false;
			for (UUID id : possibleServers) {
				ServerInfo info = getProxy().getServerInfo(id.toString());
				if (info != null) {
					getProxy().getPlayer(teleportRequest.player).connect(info);
					sent = true;
					break;
				} else {
					getLogger().warning("No info for sever: " + id);
				}
			}
			if (!sent) {
				getLogger().warning("None of the available servers could be reached!");
			}
		} else {
			getLogger().warning("Failed to find available target server!");
		}
	}

	public static int roundTile(double d) {
		if (d < 0) {
			return (int) Math.floor(d);
		} else {
			return (int) Math.ceil(d);
		}
	}

	void saveConfig(Configuration config) {
		try {
			ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, new File(getDataFolder(), "config.yml"));
		} catch (IOException e) {
			getLogger().log(Level.WARNING, "Failed to save config", e);
		}
	}

	void saveDefaultConfig() {
		if (!getDataFolder().exists()) {
			getDataFolder().mkdir();
		}
		File configFile = new File(getDataFolder(), "config.yml");
		if (!configFile.exists()) {
			try {
				configFile.createNewFile();
				try (InputStream is = getResourceAsStream("config.yml");
						OutputStream os = new FileOutputStream(configFile)) {
					ByteStreams.copy(is, os);
				}
			} catch (IOException e) {
				throw new RuntimeException("Unable to create configuration file", e);
			}
		}
	}

	@EventHandler
	public void on(ServerConnectEvent event) {
		System.out.println(event);
		if (event.getReason() == JOIN_PROXY) {
			PlayerLocation position = positionMap.get(event.getPlayer().getUniqueId());
			if (position != null) {
				routeToServerForLocation(new TeleportRequest(event.getPlayer().getUniqueId(), null, (int) position.x / 16, (int) position.y / 16, (int) position.z / 16));
			} else {
				// try sending to start tile
				routeToServerForLocation(new TeleportRequest(event.getPlayer().getUniqueId(), null, startTileX, 0, startTileZ));
			}
		}
	}

	@EventHandler
	public void on(PluginMessageEvent event) {
		if (!"MineTile".equals(event.getTag())) { return; }
		ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
		String subChannel = in.readUTF();
		byte hasData = in.readByte();
		ByteArraySerializable<?> data = null;
		if (hasData > 0) {
			String dataClass = in.readUTF();
			try {
				data = (ByteArraySerializable<?>) Class.forName(dataClass).newInstance();
			} catch (ReflectiveOperationException e) {
				getLogger().log(Level.SEVERE, "Failed to instantiate data class " + dataClass, e);
				return;
			}
			data.readFromByteArray(in);
		}

		handleClientData(subChannel, event.getSender(), data);
	}

	public void handleClientData(String subChannel, Connection sender, ByteArraySerializable data) {

	}

	public void sendToClient(String subChannel, Connection receiver, ByteArraySerializable data) {
		if (receiver instanceof Server) {
			sendToClient(subChannel, ((Server) receiver).getInfo(), data);
		} else if (receiver instanceof ProxiedPlayer) {
			sendToClient(subChannel, ((ProxiedPlayer) receiver).getServer().getInfo(), data);
		} else {
			getLogger().warning("Tried to send data to non-player / non-server receiver (" + subChannel + "): " + receiver.toString());
		}
	}

	public void sendToClient(String subChannel, ServerInfo server, ByteArraySerializable data) {
		ByteArrayDataOutput out = ByteStreams.newDataOutput();
		out.writeUTF(subChannel);
		out.writeByte(data != null ? 1 : 0);
		if (data != null) {
			out.writeUTF(data.getClass().getName());
			data.writeToByteArray(out);
		}

		server.sendData("MineTile", out.toByteArray());
	}

}
