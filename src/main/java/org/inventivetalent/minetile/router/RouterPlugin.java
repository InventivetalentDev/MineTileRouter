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
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
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

	RSet<CustomTeleport> customTeleportSet;

	RBucket<WorldEdge> worldEdgeBucket;

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
		getProxy().getPluginManager().registerListener(this, this);

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

		customTeleportSet = redisson.getSet("MineTile:CustomTeleports");
		System.out.println(config.getList("loops_example"));
		List<Map<String, Map<String, ?>>> customTpList = (List<Map<String, Map<String, ?>>>) config.getList("customTeleport");
		customTeleportSet.clear();
		customTpList.forEach(tp -> {
			System.out.println(tp);

			if (!tp.containsKey("condition")) {
				getLogger().warning("Missing condition for CustomTeleport");
				return;
			}
			if (!tp.containsKey("action")) {
				getLogger().warning("Missing action for CustomTeleport");
				return;
			}

			Map<String, Map> conditionSection = (Map<String, Map>) tp.get("condition");
			if (!conditionSection.containsKey("x") && !conditionSection.containsKey("z")) {
				getLogger().warning("CustomTeleport condition must contain at least one x or z key");
				return;
			}

			CustomTeleport.ConditionType xType = null;
			int xVal = 0;
			CustomTeleport.ConditionType zType = null;
			int zVal = 0;

			if (conditionSection.containsKey("x")) {
				Map<String, Integer> xSection = conditionSection.get("x");
				if (!xSection.containsKey("smaller") && !xSection.containsKey("greater")) {
					getLogger().warning("CustomTeleport Condition X needs either a greater or smaller key");
					return;
				}
				if (xSection.containsKey("smaller") && xSection.containsKey("greater")) {
					getLogger().warning("CustomTeleport Condition X may only container *either* a greater or smaller key");
					return;
				}

				if (xSection.containsKey("smaller")) {
					xType = CustomTeleport.ConditionType.SMALLER;
					xVal = xSection.get("smaller");
				} else if (xSection.containsKey("greater")) {
					xType = CustomTeleport.ConditionType.GREATER;
					xVal = xSection.get("greater");
				}
			}
			if (conditionSection.containsKey("z")) {
				Map<String, Integer> zSection = conditionSection.get("z");
				if (!zSection.containsKey("smaller") && !zSection.containsKey("greater")) {
					getLogger().warning("CustomTeleport Condition Z needs either a greater or smaller key");
					return;
				}
				if (zSection.containsKey("smaller") && zSection.containsKey("greater")) {
					getLogger().warning("CustomTeleport Condition Z may only container *either* a greater or smaller key");
					return;
				}

				if (zSection.containsKey("smaller")) {
					zType = CustomTeleport.ConditionType.SMALLER;
					zVal = zSection.get("smaller");
				} else if (zSection.containsKey("greater")) {
					zType = CustomTeleport.ConditionType.GREATER;
					zVal = zSection.get("greater");
				}
			}
			CustomTeleport.Condition condition = new CustomTeleport.Condition(xType, xVal, zType, zVal);

			Map<String, Integer> actionSection = (Map<String, Integer>) tp.get("action");
			CustomTeleport.Action action = new CustomTeleport.Action(
					actionSection.containsKey("x"), actionSection.getOrDefault("x", 0),
					actionSection.containsKey("z"), actionSection.getOrDefault("z", 0)
			);

			CustomTeleport customTeleport = new CustomTeleport(condition, action);
			customTeleportSet.add(customTeleport);
		});

		worldEdgeBucket = redisson.getBucket("MineTile:WorldEdge");
		Configuration edgeSection = config.getSection("worldEdge");
		WorldEdge edge = new WorldEdge(edgeSection.getInt("north", 10000000), edgeSection.getInt("east", 10000000), edgeSection.getInt("south", -10000000), edgeSection.getInt("west", -10000000));
		worldEdgeBucket.set(edge);

		RTopic teleportTopic = redisson.getTopic("MineTile:Teleports");
		teleportTopic.addListener(TeleportRequest.class, (channel, teleportRequest) -> {
			System.out.println(teleportRequest);

			routeToServerForLocation(teleportRequest, null);
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
				controlTopic.publishAsync(new ControlRequest(ControlAction.RESTART)).thenAccept((l) -> sender.sendMessage(new TextComponent("Restart request sent.")));
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
				controlTopic.publishAsync(new ControlRequest(ControlAction.SHUTDOWN)).thenAccept((l) -> sender.sendMessage(new TextComponent("Shutdown request sent.")));
			}
		});

		RTopic commandTopic = redisson.getTopic("MineTile:CommandSync");
		getProxy().getPluginManager().registerCommand(this, new Command("run-global-command", "minetile.globalcommand") {
			@Override
			public void execute(CommandSender sender, String[] args) {
				if (args.length == 0) {
					sender.sendMessage(new TextComponent("Please specify the command you want to run"));
					return;
				}
				String command = String.join(" ", args);
				commandTopic.publishAsync(new GlobalCommand(command)).thenAccept((v) -> sender.sendMessage(new TextComponent("Command sent.")));
			}
		});

		getProxy().getPluginManager().registerCommand(this, new Command("tilelist", "minetile.tilelist") {
			@Override
			public void execute(CommandSender sender, String[] args) {
				tileMap.readAllMapAsync().thenAccept(tiles -> {
					if (tiles == null || tiles.size() == 0) {
						sender.sendMessage(new TextComponent("§cThere are no tiles"));
					} else {
						if (tiles.size() == 1) {
							sender.sendMessage(new TextComponent("§7There is one tile:"));
						} else {
							sender.sendMessage(new TextComponent("§7There are " + tiles.size() + " tiles:"));
						}
						tiles.forEach((id, tile) -> {
							ServerInfo info = getProxy().getServerInfo(id.toString());
							final long pingStart = System.currentTimeMillis();
							info.ping((ping, err) -> {
								sender.sendMessage(new TextComponent("§9x" + tile.x + "  z" + tile.z + " §7[§d" + info.getAddress().getAddress().getHostAddress() + "§7]"));
								long latency = System.currentTimeMillis() - pingStart;
								if (err == null && ping != null) {
									sender.sendMessage(new TextComponent("§7-- §a" + latency + "ms§7 latency"));
								} else if (err != null) {
									sender.sendMessage(new TextComponent("§7-- §cerror: §7" + err.getMessage()));
								} else {
									sender.sendMessage(new TextComponent("§7-- §ctimed out"));
								}
								sender.sendMessage(new TextComponent("§7-- §3" + (info != null ? info.getPlayers().size() : "n/a") + "§7 players"));
							});
						});
					}
				});
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

	public void globalTeleport(UUID uuid, PlayerLocation position, Consumer<Boolean> consumer) {
		positionMap.putAsync(uuid, position).thenAccept((a) -> routeToServerForLocation(new TeleportRequest(uuid, null, position.x / 16, position.y / 16, position.z / 16), consumer));
	}

	public CompletionStage<Void> getGlobalLocation(UUID uuid, Consumer<PlayerLocation> consumer) {
		return positionMap.getAsync(uuid).thenAccept(consumer);
	}

	public void routeToServerForLocation(TeleportRequest teleportRequest, Consumer<Boolean> consumer) {
		final Set<UUID> possibleServers = new HashSet<>();
		tileMap.readAllMapAsync().thenAccept(tiles -> {
			tiles.forEach((k, v) -> {
				if (k.equals(teleportRequest.currentServer)) { return; }

				//				int minX = v.x - 1;
				//				int maxX = v.x + 1;
				//				int minZ = v.z - 1;
				//				int maxZ = v.z + 1;

				//				System.out.println("minX: " + minX);
				//				System.out.println("maxX: " + maxX);
				//				System.out.println("minZ: " + minZ);
				//				System.out.println("maxZ: " + maxZ);

				//				System.out.println("x: " + v.x);
				//				System.out.println("z: " + v.z);

				int tX = (int) Math.round(teleportRequest.x / (double) (tileSize * 2));
				int tZ = (int) Math.round(teleportRequest.z / (double) (tileSize * 2));

				//				System.out.println("tX: " + tX);
				//				System.out.println("tZ: " + tZ);

				//				if (tX >= minX && tX <= maxX && tZ >= minZ && tZ <= maxZ) {
				//					possibleServer[0] = k;
				//				}
				if (tX == v.x && tZ == v.z) {
					possibleServers.add(k);
				}
			});

			boolean sent = false;
			if (!possibleServers.isEmpty()) {
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

			if (consumer != null) {
				consumer.accept(sent);
			}
		});

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
			positionMap.getAsync(event.getPlayer().getUniqueId()).thenAccept(position -> {
				UUID currentUUID = null;
				try {
					currentUUID = UUID.fromString(event.getTarget().getName());
				} catch (Exception ignored) {
				}
				if (position != null) {
					UUID finalCurrentUUID = currentUUID;
					routeToServerForLocation(new TeleportRequest(event.getPlayer().getUniqueId(), currentUUID, position.x / 16, position.y / 16, position.z / 16), sent -> {
						if (!sent) {
							// try sending to start tile
							routeToServerForLocation(new TeleportRequest(event.getPlayer().getUniqueId(), finalCurrentUUID, startTileX, 0, startTileZ), null);
						}
					});
				} else {
					// try sending to start tile
					routeToServerForLocation(new TeleportRequest(event.getPlayer().getUniqueId(), currentUUID, startTileX, 0, startTileZ), null);
				}
			});
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
