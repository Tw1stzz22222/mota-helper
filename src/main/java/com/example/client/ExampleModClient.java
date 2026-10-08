package com.example.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExampleModClient implements ClientModInitializer {
    private static KeyBinding testKeyBinding;
    private static KeyBinding stopKeyBinding;
    private static KeyBinding pauseOrNextKeyBinding;

    private static final BlockPos DEFAULT_HIGH_FLOOR_SIGN = new BlockPos(-12, 194, -73);
    private static final Vec3d DEFAULT_HIGH_FLOOR_NAV = new Vec3d(-13.0, 193.0, -74.0);

    // --- 玩家独立聊天悬浮框数据 ---
    private static final List<Text> playerChatHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_PLAYER_CHAT_LINES = 7;
    private static boolean filterRewardMessages = true; // 是否开启 [仓库] 屏蔽

    public enum TargetFloor {
        F10C(new BlockPos(-12, 164, -73), new Vec3d(-14.0, 163.0, -74.0), "第十层", false, 0),
        F11C(new BlockPos(-12, 174, -73), new Vec3d(-14.0, 173.0, -74.0), "第十一层", false, 0),
        F12C(new BlockPos(-12, 184, -73), new Vec3d(-14.0, 183.0, -74.0), "第十二层", false, 0),
        F13C("第十三层", 0),
        F14C("第十四层", 2),
        F15C("第十五层", 4);

        private final BlockPos signPos;
        private final Vec3d navPos;
        private final String floorNameCN;
        private final boolean isSpecialMenu;
        private final int menuSlotIndex;

        TargetFloor(BlockPos signPos, Vec3d navPos, String floorNameCN, boolean isSpecialMenu, int menuSlotIndex) {
            this.signPos = signPos;
            this.navPos = navPos;
            this.floorNameCN = floorNameCN;
            this.isSpecialMenu = isSpecialMenu;
            this.menuSlotIndex = menuSlotIndex;
        }

        TargetFloor(String floorNameCN, int menuSlotIndex) {
            this(DEFAULT_HIGH_FLOOR_SIGN, DEFAULT_HIGH_FLOOR_NAV, floorNameCN, true, menuSlotIndex);
        }

        public BlockPos getSignPos() { return signPos; }
        public Vec3d getNavPos() { return navPos; }
        public String getFloorNameCN() { return floorNameCN; }
        public boolean isSpecialMenu() { return isSpecialMenu; }
        public int getMenuSlotIndex() { return menuSlotIndex; }
    }

    private enum AutoState {
        IDLE("闲置中"),
        PAUSED("已暂停"),
        WAIT_RECONNECT_SUBMENU("等待踢回重连子服菜单"),
        WAIT_MAIN_MENU("等待主菜单"),
        WAIT_CHAPTER_MENU("等待章节菜单"),
        WAIT_TOWN_LINE_MENU("等待镇线路菜单"),

        // --- 防踢与自动传送保护状态 ---
        WAIT_LOBBY_TELEPORT("【防踢保护】已进入大厅，绝对静止等待传送/识别主城核心"),
        WAIT_LOBBY_BUFFER("【防踢保护】大厅传送成功，缓冲准备恢复跑刷"),

        WAIT_ENTER_TOWN("等待进入魔塔镇缓冲"),
        ENABLE_FLY_CHECK("检查并开启飞行状态"),

        GOTO_POINT1("三段飞行寻路(-15,187,-57)"),
        WAIT_ARRIVE1("到达中转点"),

        GOTO_POINT2("Wurst前往防踢坐标(第1次)"),
        WAIT_ARRIVE2("到达防踢点(第1次)"),
        GOTO_POINT2_SECOND("Wurst前往防踢坐标(二次精确定位)"),
        WAIT_ARRIVE2_SECOND("到达防踢点(二次定位)"),
        WAIT_BEFORE_SIGN_CLICK("防踢点就位，等待12tick并校准坐标"),
        CLICK_SIGN("右键点击告示牌"),
        WAIT_COPY_LINE_MENU("等待分线菜单刷新与加载"),
        WAIT_SPECIAL_SUB_MENU("等待子分线菜单刷新"),
        WAIT_ENTER_COPY_CHECK("等待进入副本(超时/菜单检测)"),

        GOTO_11C_BOSS_CUSTOM("Wurst前往 11c Boss点"),
        WAIT_ARRIVE_11C_BOSS("到达 11c Boss点"),

        GOTO_12C_BOSS_1("12c Boss 1 寻路"),
        WAIT_12C_BOSS_1_FIGHT("等待 12c Boss 1 击杀/结算"),

        GOTO_12C_BOSS_2("12c Boss 2 寻路"),
        WAIT_12C_BOSS_2_FIGHT("等待 12c Boss 2 击杀/结算"),

        GOTO_12C_BOSS_3_HIGH("12c Boss 3 高空拉升"),
        GOTO_12C_BOSS_3_DOWN("12c Boss 3 下探战斗点"),
        WAIT_12C_BOSS_3_FIGHT("等待 12c Boss 3 击杀/结算"),

        GOTO_10C_PART1("10c寻路 1"),
        WAIT_ARRIVE_10C_1("等待到达 10c 第一段"),
        GOTO_10C_PART2("10c寻路 2"),
        WAIT_ARRIVE_10C_2("等待触发 10c 传送方块"),
        GOTO_10C_BOSS("10c寻路 3"),
        WAIT_ARRIVE_10C_BOSS("到达 10c Boss点"),

        GOTO_13C_BOSS("前往 13c Boss点"),
        WAIT_ARRIVE_13C_BOSS("到达 13c Boss点"),

        GOTO_14C_DOOR("14c 导航至门口"),
        WAIT_14C_DOOR("14c 等待到达门口并缓冲(0.6s)"),
        FLY_14C_FORWARD_15TK("14c 正前方飞行15ticks"),
        FLY_14C_TO_BOSS_RISE_Y25("14c 斜向拉升至Y25并下探Boss点"),
        WAIT_14C_BOSS_FIGHT("等待 14c Boss 击杀/结算");

        private final String desc;
        AutoState(String desc) { this.desc = desc; }
        public String getDesc() { return desc; }
    }

    private static AutoState currentState = AutoState.IDLE;

    private static TargetFloor selectedFloor = TargetFloor.F10C;
    private static String selectedTownLine = "魔塔一区";
    private static String selectedCopyLine = "一线";
    private static boolean autoSwitchLine = false;

    private static int delayTicks = 0;
    private static final int CLICK_DELAY = 12;

    private static final Vec3d TARGET_POS_1 = new Vec3d(-15.0, 187.0, -57.0);

    private static int subFlightStage = 1;
    private static final double SAFE_CRUISE_HEIGHT = 187.0;
    private static Vec3d stage1StartPos = null;

    private static int flightTimeoutTicks = 0;
    private static int bossGotoRetryCount = 0;
    private static final int BOSS_GOTO_RETRY_LIMIT = 2;
    private static final int BOSS_GOTO_TIMEOUT = 400;
    private static final double FLIGHT_HORIZONTAL_SPEED = 4.0;
    private static final double FLIGHT_VERTICAL_SPEED = 3.0;
    private static Object flightHackCache = null;
    private static Object horizontalSpeedSettingCache = null;
    private static Object verticalSpeedSettingCache = null;
    private static Method sliderSetValueMethodCache = null;
    private static Method flightHackIsEnabledMethodCache = null;
    private static Method flightHackSetEnabledMethodCache = null;
    private static boolean flightHackResolutionAttempted = false;
    private static int stage1RetryCount = 0;
    private static int flyTryCount = 0;
    private static int chatWaitTicks = 0;
    private static int copyEnterCheckTicks = 0;
    private static int menuWaitTimeoutTicks = 0;

    private static int signRetryCount = 0;
    private static int signWaitTicks = 0;
    private static int calibrationRetryCount = 0;

    private static int reconnectTimeoutTicks = 0;
    private static int menuTimeoutTicks = 0;
    private static int lobbyTimeoutTicks = 0; 

    private static int menuRetryCount = 0;
    private static final int MAX_MENU_RETRIES = 3;
    private static final int TIMEOUT_THRESHOLD = 60;

    private static boolean bossSpawnedByChat = false;
    private static boolean bossKilledByChat = false;

    private static final Vec3d BOSS_11C_POS = new Vec3d(-49.0, 7.0, -3.0);

    private static final Vec3d BOSS_12C_1_POS = new Vec3d(38.0, 88.0, 52.0);
    private static final Vec3d BOSS_12C_2_POS = new Vec3d(133.0, 118.0, -10.0);
    private static final Vec3d BOSS_12C_3_HIGH_POS = new Vec3d(64.0, 153.0, -27.0);
    private static final Vec3d BOSS_12C_3_FIGHT_POS = new Vec3d(64.0, 116.0, -27.0);

    private static final Vec3d POS_10C_1 = new Vec3d(-33.0, 127.0, -218.0);
    private static final Vec3d POS_10C_2 = new Vec3d(-162.0, 118.0, -185.0);
    private static final Vec3d BOSS_10C_POS = new Vec3d(-110.0, 211.0, -185.0);

    private static final Vec3d BOSS_13C_POS = new Vec3d(43.0, 19.0, 32.0);

    private static final Vec3d DOOR_14C_POS = new Vec3d(-91.0, 12.0, -6.0);
    private static final Vec3d BOSS_14C_POS = new Vec3d(-207.0, 6.0, -159.0);
    private static int fly14cTickCount = 0;

    private static int lineCheckRetryCount = 0;
    private static final int MAX_LINE_RETRIES = 20;

    private static boolean isWaitingForServerLore = false;
    private static int loreWaitTicks = 0;
    private static int targetSlotToCheck = -1;
    private static int currentLineIndex = 0;

    private static final List<String> currentCheckOrder = new ArrayList<>();

    private static final List<String> COPY_LINES_12C = Arrays.asList("一线", "A1", "A2", "A3");
    private static final List<String> TOWN_LINES_1_TO_10 = Arrays.asList(
            "魔塔一区", "魔塔二线", "魔塔三线", "魔塔四线", "魔塔五线",
            "魔塔六线", "魔塔七线", "魔塔八线", "魔塔九线", "魔塔十线"
    );

    private static Object wurstCmdProcessorCache = null;
    private static Method wurstProcessMethodCache = null;

    private static void switchState(MinecraftClient client, AutoState newState) {
        currentState = newState;
        resetFlightKeys(client);
    }

    private static void ensureFlying(MinecraftClient client) {
        if (client.player != null) {
            if (!client.player.getAbilities().flying) {
                client.player.getAbilities().allowFlying = true;
                client.player.getAbilities().flying = true;
                client.player.sendAbilitiesUpdate();
            }
        }
        enableWurstFlight();
    }

    private static void enableWurstFlight() {
        ensureFlightHackResolved();
        try {
            if (flightHackCache != null && flightHackIsEnabledMethodCache != null && flightHackSetEnabledMethodCache != null) {
                boolean enabled = (boolean) flightHackIsEnabledMethodCache.invoke(flightHackCache);
                if (!enabled) {
                    flightHackSetEnabledMethodCache.invoke(flightHackCache, true);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static boolean isWaitingForMenu() {
        return currentState == AutoState.WAIT_MAIN_MENU ||
               currentState == AutoState.WAIT_CHAPTER_MENU ||
               currentState == AutoState.WAIT_TOWN_LINE_MENU ||
               currentState == AutoState.WAIT_COPY_LINE_MENU ||
               currentState == AutoState.WAIT_SPECIAL_SUB_MENU ||
               currentState == AutoState.WAIT_RECONNECT_SUBMENU;
    }

    public static void startFullAutomation(TargetFloor floor, String townLine, String copyLine, boolean enableAutoSwitch) {
        selectedFloor = floor;
        selectedTownLine = formatTownLineName(townLine);
        selectedCopyLine = copyLine;
        autoSwitchLine = enableAutoSwitch;
        currentLineIndex = 0;
        lineCheckRetryCount = 0;
        isWaitingForServerLore = false;
        loreWaitTicks = 0;
        targetSlotToCheck = -1;
        currentCheckOrder.clear();
        flightTimeoutTicks = 0;
        bossGotoRetryCount = 0;
        stage1RetryCount = 0;
        flyTryCount = 0;
        chatWaitTicks = 0;
        copyEnterCheckTicks = 0;
        menuWaitTimeoutTicks = 0;
        signRetryCount = 0;
        signWaitTicks = 0;
        calibrationRetryCount = 0;
        reconnectTimeoutTicks = 0;
        menuTimeoutTicks = 0;
        lobbyTimeoutTicks = 0;
        menuRetryCount = 0;
        fly14cTickCount = 0;
        bossSpawnedByChat = false;
        bossKilledByChat = false;

        setFlightSpeeds();

        startMenuSequence();
    }

    private static void startMenuSequence() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            switchState(client, AutoState.WAIT_MAIN_MENU);
            menuTimeoutTicks = 0;
            delayTicks = CLICK_DELAY;

            if (client.currentScreen instanceof ChatScreen) {
                client.player.sendMessage(Text.literal("§e[魔塔助手] 检测到您正在使用聊天框，已暂停自动导航，请关闭聊天框后将自动恢复。"), false);
                return;
            }

            if (menuRetryCount > 0) {
                client.player.sendMessage(Text.literal(String.format("§6[魔塔助手] 菜单响应超时，正在执行第 %d 次重新导航尝试...", menuRetryCount)), false);
            } else {
                client.player.sendMessage(Text.literal(String.format("§b[魔塔助手] 启动/重置流程 -> 层数:§e%s §b| 当前镇线:§e%s §b| 分线:§e%s",
                        selectedFloor.name(), selectedTownLine, selectedCopyLine)), false);
            }

            if (client.currentScreen != null) {
                client.setScreen(null);
            }

            client.player.networkHandler.sendChatMessage("/menu");
        }
    }

    private static final String[] CN_NUMS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    private static String formatTownLineName(String input) {
        if (input == null || input.isEmpty()) return "魔塔一区";
        if (input.startsWith("魔塔")) return input;

        Matcher matcher = Pattern.compile("(\\d+|[一二三四五六七八九十]+)").matcher(input);
        if (matcher.find()) {
            String val = matcher.group(1);
            int num = -1;
            try {
                num = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                for (int i = 0; i < CN_NUMS.length; i++) {
                    if (CN_NUMS[i].equals(val)) {
                        num = i + 1;
                        break;
                    }
                }
            }
            if (num == 1) return "魔塔一区";
            if (num > 1 && num <= 10) return "魔塔" + CN_NUMS[num - 1] + "线";
        }
        return input;
    }

    private static int getTownIndex(String townLineStr) {
        String numStr = extractLineNumber(townLineStr);
        for (int i = 0; i < TOWN_LINES_1_TO_10.size(); i++) {
            if (extractLineNumber(TOWN_LINES_1_TO_10.get(i)).equals(numStr)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void onInitializeClient() {
        // 1. 启动 QQ 转发的内置 HTTP 服务器 (支持多人服务器全局广播，并去除了前缀)
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(25588), 0);
            
            server.createContext("/say", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    if ("POST".equals(exchange.getRequestMethod())) {
                        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                            // 解析 JSON
                            Map data = new Gson().fromJson(reader, Map.class);
                            String message = (String) data.getOrDefault("message", "");

                            if (!message.isEmpty()) {
                                MinecraftClient client = MinecraftClient.getInstance();
                                // 安全切回主线程，直接使用 sendChatMessage 发送纯文本内容，没有任何前缀
                                client.execute(() -> {
                                    if (client.player != null && client.player.networkHandler != null) {
                                        client.player.networkHandler.sendChatMessage(message);
                                    }
                                });
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        
                        // 响应 HTTP 请求成功
                        String response = "OK";
                        exchange.sendResponseHeaders(200, response.getBytes().length);
                        exchange.getResponseBody().write(response.getBytes());
                    }
                    exchange.close();
                }
            });

            server.setExecutor(null);
            server.start();
            System.out.println(">>> [Minecraft QQ Bridge] HTTP 服务器已成功启动于 25588 端口！ <<<");

        } catch (IOException e) {
            System.err.println(">>> [Minecraft QQ Bridge] HTTP 服务器启动失败 (端口可能被占用) <<<");
            e.printStackTrace();
        }

        // 2. 按键与事件注册
        testKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.example.test", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.example.test"
        ));

        stopKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.example.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, "category.example.test"
        ));

        pauseOrNextKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.example.pause_next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, "category.example.test"
        ));

        ClientReceiveMessageEvents.ALLOW_GAME.register((text, overlay) -> {
            String rawMsg = text.getString();
            String cleanMsg = rawMsg.replaceAll("§[0-9a-fk-orA-FK-OR]", "");

            boolean isPlayerChat = cleanMsg.matches(".*\\[LV\\.\\d+\\].*") || cleanMsg.contains("↪");

            if (isPlayerChat) {
                playerChatHistory.add(text);
                if (playerChatHistory.size() > MAX_PLAYER_CHAT_LINES) {
                    playerChatHistory.remove(0);
                }
            }

            if (filterRewardMessages && (cleanMsg.contains("[仓库]") || cleanMsg.contains("获得了") || cleanMsg.contains("给予了"))) {
                return false;
            }

            if (cleanMsg.contains("[魔塔调试]") || cleanMsg.contains("[魔塔助手]")) return true;

            if (((cleanMsg.contains("[玩家系统]") && cleanMsg.contains("已成功登录")) || cleanMsg.contains("数据加载完成")) && currentState == AutoState.WAIT_LOBBY_TELEPORT) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§a[魔塔助手] 检测到登录成功/数据加载完成回显，准备恢复跑刷流程..."), false);
                    switchState(client, AutoState.WAIT_LOBBY_BUFFER);
                    delayTicks = 40; 
                }
            }

            if (cleanMsg.contains("出现在第十二层") || cleanMsg.contains("出现在第十四层")) {
                bossSpawnedByChat = true;
            }
            if (cleanMsg.contains("成功击杀")) {
                if (currentState == AutoState.WAIT_12C_BOSS_1_FIGHT ||
                    currentState == AutoState.WAIT_12C_BOSS_2_FIGHT ||
                    currentState == AutoState.WAIT_12C_BOSS_3_FIGHT ||
                    currentState == AutoState.WAIT_14C_BOSS_FIGHT) {
                    bossKilledByChat = true;
                }
            }
            if (cleanMsg.contains("请选择传送的子服") || cleanMsg.contains("连接已断开") || cleanMsg.contains("被踢出") || cleanMsg.contains("正在匹配") || cleanMsg.contains("登录大厅")) {
                if (currentState != AutoState.IDLE && currentState != AutoState.PAUSED) {
                    switchState(MinecraftClient.getInstance(), AutoState.WAIT_LOBBY_TELEPORT);
                    lobbyTimeoutTicks = 0;
                    MinecraftClient.getInstance().player.sendMessage(Text.literal("§c[魔塔助手] 检测到被T飞/进入大厅，已全面锁死移动，等待自动传送或识别主国核心！"), false);
                }
            }
            return true;
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;

            int screenWidth = client.getWindow().getScaledWidth();

            int statusWidth = 260;
            int statusX = screenWidth - statusWidth - 5;
            int statusY = 10;

            drawContext.fill(statusX - 4, statusY - 4, statusX + statusWidth, statusY + 48, 160 << 24);
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§e§l魔塔自动化助手"), statusX, statusY, 0xFFFFFF);

            String statusColor = (currentState == AutoState.IDLE) ? "§7" : (currentState == AutoState.PAUSED ? "§c" : "§a");
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("状态: " + statusColor + currentState.getDesc()), statusX, statusY + 12, 0xFFFFFF);
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("镇线: §b" + selectedTownLine + " §8| §f层数: §b" + selectedFloor.name()), statusX, statusY + 24, 0xFFFFFF);

            String lineInfo = selectedCopyLine + (autoSwitchLine ? " (抢线开启)" : " (抢线关闭)");
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("目标分线: §b" + lineInfo), statusX, statusY + 36, 0xFFFFFF);

            int chatWidth = 400;
            int chatX = screenWidth - chatWidth - 5;
            int chatY = statusY + 50;
            int displayLines = playerChatHistory.isEmpty() ? 1 : playerChatHistory.size();
            int chatHeight = 14 + displayLines * 10;

            drawContext.fill(chatX - 4, chatY - 4, chatX + chatWidth, chatY + chatHeight, 160 << 24);
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§b§l💬 玩家聊天频道"), chatX, chatY, 0xFFFFFF);

            if (playerChatHistory.isEmpty()) {
                drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§7(等待玩家发言中...)"), chatX, chatY + 12, 0xFFFFFF);
            } else {
                for (int i = 0; i < playerChatHistory.size(); i++) {
                    Text msgText = playerChatHistory.get(i);
                    drawContext.drawTextWithShadow(client.textRenderer, msgText, chatX, chatY + 12 + (i * 10), 0xFFFFFF);
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            client.options.pauseOnLostFocus = false;

            while (testKeyBinding.wasPressed()) {
                client.setScreen(new FloorSelectScreen());
            }

            while (stopKeyBinding.wasPressed()) {
                switchState(client, AutoState.IDLE);
                client.player.sendMessage(Text.literal("§c[魔塔助手] 收到 '=' 键信号，程序已彻底强制停止！"), false);
            }

            while (pauseOrNextKeyBinding.wasPressed()) {
                if (currentState != AutoState.PAUSED && currentState != AutoState.IDLE) {
                    switchState(client, AutoState.PAUSED);
                    client.player.sendMessage(Text.literal("§e[魔塔助手] 当前流程已暂停。再次按下 '-' 键将直接跳过当前，跳转并开始下一个循环！"), false);
                } else if (currentState == AutoState.PAUSED) {
                    client.player.sendMessage(Text.literal("§a[魔塔助手] 再次按下 '-' 键，正在强制跳过并跳转至下一个目标循环..."), false);
                    skipCurrentAndAdvanceNext(client);
                }
            }

            if (currentState == AutoState.PAUSED || currentState == AutoState.IDLE) return;

            if (currentState != AutoState.WAIT_LOBBY_TELEPORT && currentState != AutoState.WAIT_LOBBY_BUFFER) {
                ScreenHandler activeHandler = client.player.currentScreenHandler;
                if (activeHandler != null && !activeHandler.slots.isEmpty()) {
                    for (int i = 0; i < activeHandler.slots.size(); i++) {
                        ItemStack stack = activeHandler.getSlot(i).getStack();
                        if (!stack.isEmpty() && stack.getName().getString().contains("进入魔塔主城")) {
                            client.player.sendMessage(Text.literal("§e[魔塔助手] 检测到大厅魔塔主城菜单，自动切换至防踢静止状态并点击主城核心！"), false);
                            switchState(client, AutoState.WAIT_LOBBY_TELEPORT);
                            lobbyTimeoutTicks = 0;
                            client.interactionManager.clickSlot(activeHandler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            delayTicks = 40;
                            return;
                        }
                    }
                }
            }

            if (currentState == AutoState.WAIT_LOBBY_TELEPORT) {
                resetFlightKeys(client);

                ScreenHandler handler = client.player.currentScreenHandler;
                if (handler != null && !handler.slots.isEmpty()) {
                    boolean foundMenu = false;
                    for (int i = 0; i < handler.slots.size(); i++) {
                        ItemStack stack = handler.getSlot(i).getStack();
                        if (!stack.isEmpty() && stack.getName().getString().contains("进入魔塔主城")) {
                            foundMenu = true;
                            client.player.sendMessage(Text.literal("§a[魔塔助手] 成功识别并点击“进入魔塔主城”核心！"), false);
                            client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            delayTicks = 40;
                            lobbyTimeoutTicks = 0;
                            break;
                        }
                    }
                    if (foundMenu) {
                        return;
                    }
                }

                lobbyTimeoutTicks++;
                if (lobbyTimeoutTicks > 100) {
                    lobbyTimeoutTicks = 0;
                    client.player.sendMessage(Text.literal("§e[魔塔助手] 大厅防踢/传送状态超时，自动切入缓冲区准备恢复跑刷..."), false);
                    switchState(client, AutoState.WAIT_LOBBY_BUFFER);
                    delayTicks = 40;
                }
                return;
            }

            if (currentState == AutoState.WAIT_LOBBY_BUFFER) {
                resetFlightKeys(client);
                if (delayTicks > 0) {
                    delayTicks--;
                    return;
                }
                client.player.sendMessage(Text.literal("§a[魔塔助手] 传送大厅缓冲完毕，正在重新启动跑刷菜单流程（放弃上一路线）..."), false);
                startMenuSequence();
                return;
            }

            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (currentState == AutoState.WAIT_RECONNECT_SUBMENU) {
                reconnectTimeoutTicks++;
                ScreenHandler handler = client.player.currentScreenHandler;

                boolean clicked = false;
                if (handler != null && !handler.slots.isEmpty()) {
                    clicked = clickSlotByKeyword(handler, client, "魔塔") || clickSlotByKeyword(handler, client, "进入");
                }

                if (reconnectTimeoutTicks % 30 == 0 && !clicked) {
                    client.player.networkHandler.sendChatMessage("/menu");
                }

                if (clicked || reconnectTimeoutTicks > 80) {
                    reconnectTimeoutTicks = 0;
                    client.player.sendMessage(Text.literal("§e[魔塔助手] 重连已响应或超时解封，正在推进至下一个循环..."), false);
                    skipCurrentAndAdvanceNext(client);
                    return;
                }
            }

            if (currentState == AutoState.WAIT_MAIN_MENU || currentState == AutoState.WAIT_CHAPTER_MENU) {
                if (!(client.currentScreen instanceof ChatScreen)) {
                    menuTimeoutTicks++;

                    if (menuTimeoutTicks > TIMEOUT_THRESHOLD) {
                        menuTimeoutTicks = 0;
                        if (menuRetryCount < MAX_MENU_RETRIES) {
                            menuRetryCount++;
                            startMenuSequence();
                        } else {
                            client.player.sendMessage(Text.literal("§c[魔塔助手] 多次尝试打开/导航主菜单失败，已自动中止，请检查网络或服务器状态。"), false);
                            switchState(client, AutoState.IDLE);
                            menuRetryCount = 0;
                        }
                        return;
                    }
                }
            }

            boolean isHandlingMenu = isWaitingForMenu();
            ScreenHandler activeHandler = client.player.currentScreenHandler;

            if (isHandlingMenu && activeHandler != null && !activeHandler.slots.isEmpty()) {
                switch (currentState) {
                    case WAIT_MAIN_MENU:
                        if (clickSlotByKeyword(activeHandler, client, "魔塔")) {
                            menuRetryCount = 0;
                            switchState(client, AutoState.WAIT_CHAPTER_MENU);
                            menuTimeoutTicks = 0;
                            delayTicks = CLICK_DELAY;
                        }
                        break;

                    case WAIT_CHAPTER_MENU:
                        if (clickSlotByKeyword(activeHandler, client, "魔塔第一章")) {
                            menuRetryCount = 0;
                            switchState(client, AutoState.WAIT_TOWN_LINE_MENU);
                            lineCheckRetryCount = 0;
                            menuTimeoutTicks = 0;
                            delayTicks = CLICK_DELAY + 5;
                        }
                        break;

                    case WAIT_TOWN_LINE_MENU:
                        boolean lineClicked = clickSlotByKeyword(activeHandler, client, selectedTownLine);
                        if (!lineClicked) {
                            String numPart = extractLineNumber(selectedTownLine);
                            if (!numPart.isEmpty()) {
                                String[] fallbackKeywords = new String[]{ "魔塔" + numPart, "魔塔 " + numPart, numPart + "线", numPart + "区" };
                                for (String kw : fallbackKeywords) {
                                    if (clickSlotByKeyword(activeHandler, client, kw)) {
                                        lineClicked = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (lineClicked) {
                            switchState(client, AutoState.WAIT_ENTER_TOWN);
                            delayTicks = 40;
                            lineCheckRetryCount = 0;
                            signRetryCount = 0;
                            calibrationRetryCount = 0;
                            client.player.sendMessage(Text.literal("§e[魔塔助手] 已选择 [" + selectedTownLine + "]，等待加载..."), false);
                        } else {
                            if (lineCheckRetryCount < 15) {
                                lineCheckRetryCount++;
                                delayTicks = 5;
                            } else {
                                client.player.sendMessage(Text.literal("§c[魔塔助手] 未找到 [" + selectedTownLine + "]，重新发送 /menu 重试..."), false);
                                lineCheckRetryCount = 0;
                                startMenuSequence();
                            }
                        }
                        break;

                    case WAIT_COPY_LINE_MENU:
                    case WAIT_SPECIAL_SUB_MENU:
                        menuWaitTimeoutTicks++;
                        if (menuWaitTimeoutTicks > 80) {
                            menuWaitTimeoutTicks = 0;
                            client.player.sendMessage(Text.literal("§c[魔塔助手] 分线菜单响应超时/卡死，自动跳过并切入下一循环！"), false);
                            if (client.currentScreen != null) client.setScreen(null);
                            skipCurrentAndAdvanceNext(client);
                            return;
                        }

                        boolean success = handleCopyLineSelectionWithDelay(activeHandler, client);
                        if (success) {
                            menuWaitTimeoutTicks = 0;
                            lineCheckRetryCount = 0;
                            signRetryCount = 0;
                            stage1RetryCount = 0;
                            calibrationRetryCount = 0;
                            if (selectedFloor == TargetFloor.F10C) {
                                delayTicks = 40; switchState(client, AutoState.GOTO_10C_PART1);
                            } else if (selectedFloor == TargetFloor.F11C) {
                                delayTicks = 10; switchState(client, AutoState.GOTO_11C_BOSS_CUSTOM);
                            } else if (selectedFloor == TargetFloor.F12C) {
                                delayTicks = 20; switchState(client, AutoState.GOTO_12C_BOSS_1);
                                flightTimeoutTicks = 0; chatWaitTicks = 0;
                                bossSpawnedByChat = false; bossKilledByChat = false;
                                client.player.sendMessage(Text.literal("§a[魔塔助手] 已成功进入 12c " + selectedCopyLine + "！"), false);
                            } else if (selectedFloor == TargetFloor.F13C) {
                                delayTicks = 40; switchState(client, AutoState.GOTO_13C_BOSS);
                            } else if (selectedFloor == TargetFloor.F14C) {
                                delayTicks = 20; switchState(client, AutoState.GOTO_14C_DOOR);
                                flightTimeoutTicks = 0; chatWaitTicks = 0; fly14cTickCount = 0;
                                bossSpawnedByChat = false; bossKilledByChat = false;
                                client.player.sendMessage(Text.literal("§a[魔塔助手] 已成功进入 14c " + selectedCopyLine + "！"), false);
                            } else {
                                switchState(client, AutoState.IDLE);
                            }
                        }
                        break;

                    default:
                        break;
                }
            }

            if (!isHandlingMenu) {
                Vec3d playerPos = client.player.getPos();

                if (currentState == AutoState.WAIT_ENTER_TOWN) {
                    switchState(client, AutoState.ENABLE_FLY_CHECK);
                    flyTryCount = 0;
                    delayTicks = 5;
                }
                else if (currentState == AutoState.ENABLE_FLY_CHECK) {
                    boolean isFlying = client.player.getAbilities().flying || !client.player.isOnGround();

                    if (isFlying) {
                        switchState(client, AutoState.GOTO_POINT1);
                        subFlightStage = 1; stage1StartPos = playerPos; flightTimeoutTicks = 0; delayTicks = 5;
                    } else {
                        if (flyTryCount < 5) {
                            executeWurstCommandDirectly("t flight");
                            setFlightSpeeds();
                            client.player.jump();
                            flyTryCount++;
                            delayTicks = 15;
                        } else {
                            client.player.jump();
                            switchState(client, AutoState.GOTO_POINT1);
                            subFlightStage = 1; stage1StartPos = playerPos; flightTimeoutTicks = 0; delayTicks = 5;
                        }
                    }
                }
                else if (currentState == AutoState.GOTO_POINT1) {
                    flightTimeoutTicks++;

                    if (flightTimeoutTicks > 150) {
                        flightTimeoutTicks = 0;

                        if (stage1RetryCount < 1) {
                            stage1RetryCount++;
                            client.player.sendMessage(Text.literal(String.format("§c[魔塔助手] 三段飞行未到达(-15,187,-57)，正在重新进入【%s】重试 (第 %d/1 次)...",
                                    selectedTownLine, stage1RetryCount)), false);
                            startMenuSequence();
                        } else {
                            stage1RetryCount = 0;
                            client.player.sendMessage(Text.literal("§c[魔塔助手] 三段飞行寻路重试失败，自动跳过并推进至下一线/镇线！"), false);
                            skipCurrentAndAdvanceNext(client);
                        }
                        return;
                    }

                    boolean reachedTarget1 = executeThreeStageFlight(client, playerPos, TARGET_POS_1);
                    if (reachedTarget1) {
                        double distToNav = playerPos.distanceTo(TARGET_POS_1);
                        if (distToNav > 2.5 || playerPos.y > 187.5) {
                            client.player.sendMessage(Text.literal(String.format("§e[魔塔助手] 检查坐标: 高度或位置未完全到位(Y=%.1f)，正在下探降高...", playerPos.y)), false);
                            navigateToTarget(client, playerPos, TARGET_POS_1);
                            return;
                        }

                        flightTimeoutTicks = 0;
                        stage1RetryCount = 0;
                        switchState(client, AutoState.GOTO_POINT2);
                        delayTicks = 5;
                    }
                }
                else if (currentState == AutoState.GOTO_POINT2) {
                    Vec3d nav = selectedFloor.getNavPos();
                    executeWurstCommandDirectly(String.format("goto %.0f %.0f %.0f", nav.x, nav.y, nav.z));
                    switchState(client, AutoState.WAIT_ARRIVE2);
                    flightTimeoutTicks = 0;
                }
                else if (currentState == AutoState.WAIT_ARRIVE2) {
                    flightTimeoutTicks++;
                    Vec3d nav = selectedFloor.getNavPos();

                    if (playerPos.squaredDistanceTo(nav) < 9.0 || flightTimeoutTicks > 200) {
                        switchState(client, AutoState.GOTO_POINT2_SECOND);
                        flightTimeoutTicks = 0;
                    }
                }
                else if (currentState == AutoState.GOTO_POINT2_SECOND) {
                    Vec3d nav = selectedFloor.getNavPos();
                    executeWurstCommandDirectly(String.format("goto %.0f %.0f %.0f", nav.x, nav.y, nav.z));
                    switchState(client, AutoState.WAIT_ARRIVE2_SECOND);
                    flightTimeoutTicks = 0;
                }
                else if (currentState == AutoState.WAIT_ARRIVE2_SECOND) {
                    flightTimeoutTicks++;
                    Vec3d nav = selectedFloor.getNavPos();

                    if (playerPos.squaredDistanceTo(nav) < 4.0 || flightTimeoutTicks > 150) {
                        switchState(client, AutoState.WAIT_BEFORE_SIGN_CLICK);
                        signWaitTicks = 12;
                        flightTimeoutTicks = 0;
                    }
                }
                else if (currentState == AutoState.WAIT_BEFORE_SIGN_CLICK) {
                    resetFlightKeys(client);
                    Vec3d navTarget = selectedFloor.getNavPos();

                    double xDiff = Math.abs(playerPos.x - navTarget.x);
                    double zDiff = Math.abs(playerPos.z - navTarget.z);

                    if (xDiff > 2.5 || zDiff > 2.5) {
                        calibrationRetryCount++;
                        if (calibrationRetryCount > 2) {
                            client.player.sendMessage(Text.literal("§c[魔塔助手] 防踢点反复校准失败，路径寻路卡死！正在脱离当前位置切入下一路线..."), false);
                            calibrationRetryCount = 0;
                            skipCurrentAndAdvanceNext(client);
                            return;
                        }

                        client.player.sendMessage(Text.literal(String.format("§e[魔塔助手] 防踢点坐标偏差过大 (dx=%.2f, dz=%.2f)，重新执行导航校准 (%d/2)...", xDiff, zDiff, calibrationRetryCount)), false);
                        switchState(client, AutoState.GOTO_POINT2);
                        delayTicks = 5;
                        return;
                    }

                    calibrationRetryCount = 0;
                    lookAtBlockPos(client, selectedFloor.getSignPos());

                    signWaitTicks--;
                    if (signWaitTicks <= 0) {
                        switchState(client, AutoState.CLICK_SIGN);
                    }
                }
                else if (currentState == AutoState.CLICK_SIGN) {
                    if (client.interactionManager != null && client.player != null) {
                        BlockPos targetSign = selectedFloor.getSignPos();
                        lookAtBlockPos(client, targetSign);

                        Vec3d eyePos = client.player.getEyePos();
                        Vec3d blockCenter = Vec3d.ofCenter(targetSign);
                        Direction side = Direction.getFacing(
                            (float)(eyePos.x - blockCenter.x),
                            (float)(eyePos.y - blockCenter.y),
                            (float)(eyePos.z - blockCenter.z)
                        );

                        BlockHitResult hitResult = new BlockHitResult(blockCenter, side, targetSign, false);

                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                        client.player.swingHand(Hand.MAIN_HAND);

                        copyEnterCheckTicks = 0;
                        menuWaitTimeoutTicks = 0;
                        switchState(client, AutoState.WAIT_ENTER_COPY_CHECK);
                        delayTicks = 0;
                    }
                }
                else if (currentState == AutoState.WAIT_ENTER_COPY_CHECK) {
                    copyEnterCheckTicks++;

                    if (client.player.currentScreenHandler != null && !client.player.currentScreenHandler.slots.isEmpty()) {
                        switchState(client, selectedFloor.isSpecialMenu() ? AutoState.WAIT_SPECIAL_SUB_MENU : AutoState.WAIT_COPY_LINE_MENU);
                        copyEnterCheckTicks = 0;
                        menuWaitTimeoutTicks = 0;
                        signRetryCount = 0;
                        return;
                    }

                    if (copyEnterCheckTicks >= 15) {
                        copyEnterCheckTicks = 0;

                        if (signRetryCount < 1) {
                            signRetryCount++;
                            client.player.sendMessage(Text.literal("§c[魔塔助手] 告示牌未响应（第 1 次尝试失败），重新导航至防踢点位尝试第 2 次..."), false);
                            switchState(client, AutoState.GOTO_POINT2);
                            delayTicks = 10;
                        } else {
                            signRetryCount = 0;
                            client.player.sendMessage(Text.literal(String.format("§c[魔塔助手] 两次尝试右键告示牌均无响应，跳过【%s - %s】并进入下一循环！", selectedTownLine, selectedCopyLine)), false);
                            skipCurrentAndAdvanceNext(client);
                        }
                    }
                }
                else if (currentState == AutoState.GOTO_11C_BOSS_CUSTOM) {
                    executeWurstCommandDirectly("goto -49 7 -3");
                    switchState(client, AutoState.WAIT_ARRIVE_11C_BOSS);
                    flightTimeoutTicks = 0;
                }
                else if (currentState == AutoState.WAIT_ARRIVE_11C_BOSS) {
                    handleBossGotoArrival(client, playerPos, BOSS_11C_POS, AutoState.IDLE, "goto -49 7 -3", 0);
                }
                else if (currentState == AutoState.GOTO_12C_BOSS_1) {
                    handleBossNavigation(client, playerPos, BOSS_12C_1_POS, AutoState.WAIT_12C_BOSS_1_FIGHT);
                }
                else if (currentState == AutoState.WAIT_12C_BOSS_1_FIGHT) {
                    handle12cBossWait15s(client, AutoState.GOTO_12C_BOSS_2);
                }
                else if (currentState == AutoState.GOTO_12C_BOSS_2) {
                    handleBossNavigation(client, playerPos, BOSS_12C_2_POS, AutoState.WAIT_12C_BOSS_2_FIGHT);
                }
                else if (currentState == AutoState.WAIT_12C_BOSS_2_FIGHT) {
                    handle12cBossWait15s(client, AutoState.GOTO_12C_BOSS_3_HIGH);
                }
                else if (currentState == AutoState.GOTO_12C_BOSS_3_HIGH) {
                    flightTimeoutTicks++;
                    if (flightTimeoutTicks > 400 || navigateVerticalFirst(client, playerPos, BOSS_12C_3_HIGH_POS, 2.5)) {
                        switchState(client, AutoState.GOTO_12C_BOSS_3_DOWN);
                        flightTimeoutTicks = 0;
                    }
                }
                else if (currentState == AutoState.GOTO_12C_BOSS_3_DOWN) {
                    handleBossNavigation(client, playerPos, BOSS_12C_3_FIGHT_POS, AutoState.WAIT_12C_BOSS_3_FIGHT);
                }
                else if (currentState == AutoState.WAIT_12C_BOSS_3_FIGHT) {
                    chatWaitTicks++;
                    if (bossKilledByChat || chatWaitTicks >= 400) {
                        client.player.sendMessage(Text.literal("§a[魔塔助手] 当前 12c " + selectedCopyLine + " (Boss 3) 结算/超时完成！"), false);
                        resetFlightKeys(client);
                        bossSpawnedByChat = false;
                        bossKilledByChat = false;
                        chatWaitTicks = 0;

                        if (!autoSwitchLine && selectedFloor == TargetFloor.F12C) {
                            skipCurrentAndAdvanceNext(client);
                        } else {
                            switchState(client, AutoState.IDLE);
                        }
                    }
                }
                else if (currentState == AutoState.GOTO_10C_PART1) {
                    executeWurstCommandDirectly("goto -33 127 -218");
                    switchState(client, AutoState.WAIT_ARRIVE_10C_1);
                    flightTimeoutTicks = 0;
                }
                else if (currentState == AutoState.WAIT_ARRIVE_10C_1) {
                    handleBossGotoArrival(client, playerPos, POS_10C_1, AutoState.GOTO_10C_PART2, "goto -33 127 -218", 5);
                }
                else if (currentState == AutoState.GOTO_10C_PART2) {
                    executeWurstCommandDirectly("goto -162 118 -185");
                    switchState(client, AutoState.WAIT_ARRIVE_10C_2);
                }
                else if (currentState == AutoState.WAIT_ARRIVE_10C_2) {
                    handleBossGotoArrival(client, playerPos, POS_10C_2, AutoState.GOTO_10C_BOSS, "goto -162 118 -185", 5);
                }
                else if (currentState == AutoState.GOTO_10C_BOSS) {
                    executeWurstCommandDirectly("goto -110 211 -185");
                    switchState(client, AutoState.WAIT_ARRIVE_10C_BOSS);
                }
                else if (currentState == AutoState.WAIT_ARRIVE_10C_BOSS) {
                    handleBossGotoArrival(client, playerPos, BOSS_10C_POS, AutoState.IDLE, "goto -110 211 -185", 0);
                }
                else if (currentState == AutoState.GOTO_13C_BOSS) {
                    executeWurstCommandDirectly("goto 43 19 32");
                    switchState(client, AutoState.WAIT_ARRIVE_13C_BOSS);
                    flightTimeoutTicks = 0;
                }
                else if (currentState == AutoState.WAIT_ARRIVE_13C_BOSS) {
                    handleBossGotoArrival(client, playerPos, BOSS_13C_POS, AutoState.IDLE, "goto 43 19 32", 0);
                }
                else if (currentState == AutoState.GOTO_14C_DOOR) {
                    executeWurstCommandDirectly("goto -91 12 -6");
                    switchState(client, AutoState.WAIT_14C_DOOR);
                    flightTimeoutTicks = 0;
                }
                else if (currentState == AutoState.WAIT_14C_DOOR) {
                    flightTimeoutTicks++;
                    if (playerPos.squaredDistanceTo(DOOR_14C_POS) < 9.0 || flightTimeoutTicks > 200) {
                        resetFlightKeys(client);
                        delayTicks = 12;
                        switchState(client, AutoState.FLY_14C_FORWARD_15TK);
                        fly14cTickCount = 0;
                    }
                }
                else if (currentState == AutoState.FLY_14C_FORWARD_15TK) {
                    ensureFlying(client);

                    client.options.sneakKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    if (client.player.input != null) {
                        client.player.input.sneaking = false;
                        client.player.input.jumping = false;
                    }

                    client.options.forwardKey.setPressed(true);
                    if (client.player.input != null) {
                        client.player.input.pressingForward = true;
                    }

                    fly14cTickCount++;
                    if (fly14cTickCount >= 15) {
                        fly14cTickCount = 0;
                        switchState(client, AutoState.FLY_14C_TO_BOSS_RISE_Y25);
                        flightTimeoutTicks = 0;
                    }
                }
                else if (currentState == AutoState.FLY_14C_TO_BOSS_RISE_Y25) {
                    ensureFlying(client);
                    flightTimeoutTicks++;

                    double dx = BOSS_14C_POS.x - playerPos.x;
                    double dz = BOSS_14C_POS.z - playerPos.z;
                    double horizDist = Math.sqrt(dx * dx + dz * dz);

                    if (horizDist > 0.3) {
                        float targetYaw = (float) (Math.atan2(dz, dx) * (180 / Math.PI)) - 90.0F;
                        client.player.setYaw(targetYaw);
                    }

                    client.options.forwardKey.setPressed(true);
                    if (client.player.input != null) {
                        client.player.input.pressingForward = true;
                    }

                    if (horizDist > 12.0) {
                        if (playerPos.y < 35.0) {
                            client.options.jumpKey.setPressed(true);
                            client.options.sneakKey.setPressed(false);
                            if (client.player.input != null) {
                                client.player.input.jumping = true;
                                client.player.input.sneaking = false;
                            }
                            client.player.setPitch(-25.0F);
                        } else {
                            client.options.jumpKey.setPressed(false);
                            client.options.sneakKey.setPressed(false);
                            if (client.player.input != null) {
                                client.player.input.jumping = false;
                                client.player.input.sneaking = false;
                            }
                            client.player.setPitch(0.0F);
                        }
                    } else {
                        double dy = BOSS_14C_POS.y - playerPos.y;
                        float targetPitch = (float) (-(Math.atan2(dy, horizDist) * (180 / Math.PI)));
                        if (targetPitch > 60.0F) targetPitch = 60.0F;
                        if (targetPitch < -60.0F) targetPitch = -60.0F;
                        client.player.setPitch(targetPitch);

                        boolean needSneak = playerPos.y > BOSS_14C_POS.y + 1.5;
                        boolean needJump = playerPos.y < BOSS_14C_POS.y - 0.5;

                        client.options.sneakKey.setPressed(needSneak);
                        client.options.jumpKey.setPressed(needJump);
                        if (client.player.input != null) {
                            client.player.input.sneaking = needSneak;
                            client.player.input.jumping = needJump;
                        }
                    }

                    boolean arrived = playerPos.distanceTo(BOSS_14C_POS) < 3.0 ||
                                      (horizDist < 2.5 && Math.abs(playerPos.y - BOSS_14C_POS.y) < 3.0);

                    if (arrived || flightTimeoutTicks > 400) {
                        resetFlightKeys(client);
                        switchState(client, AutoState.WAIT_14C_BOSS_FIGHT);
                        chatWaitTicks = 0;
                        bossSpawnedByChat = false;
                        bossKilledByChat = false;
                    }
                }
                else if (currentState == AutoState.WAIT_14C_BOSS_FIGHT) {
                    chatWaitTicks++;
                    if (bossKilledByChat || chatWaitTicks > 600) {
                        client.player.sendMessage(Text.literal("§a[魔塔助手] 14c " + selectedCopyLine + (bossKilledByChat ? " Boss已被击杀！" : " 触发30秒预防超时！") + " 自动切换下一路线..."), false);
                        bossSpawnedByChat = false;
                        bossKilledByChat = false;
                        chatWaitTicks = 0;
                        skipCurrentAndAdvanceNext(client);
                    }
                }
            }
        });
    }

    private static void lookAtBlockPos(MinecraftClient client, BlockPos targetSign) {
        if (client.player == null) return;

        double dx = (targetSign.getX() + 0.5) - client.player.getX();
        double dy = (targetSign.getY() + 0.5) - client.player.getEyeY();
        double dz = (targetSign.getZ() + 0.5) - client.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.atan2(dz, dx) * (180 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Math.atan2(dy, distance) * (180 / Math.PI)));

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private static void handleBossGotoArrival(MinecraftClient client, Vec3d playerPos, Vec3d target, AutoState nextState, String gotoCommand, int arriveDelay) {
        flightTimeoutTicks++;
        if (playerPos.squaredDistanceTo(target) < 6.25) {
            flightTimeoutTicks = 0;
            bossGotoRetryCount = 0;
            switchState(client, nextState);
            delayTicks = arriveDelay;
            return;
        }
        if (flightTimeoutTicks > BOSS_GOTO_TIMEOUT) {
            flightTimeoutTicks = 0;
            if (bossGotoRetryCount < BOSS_GOTO_RETRY_LIMIT) {
                bossGotoRetryCount++;
                executeWurstCommandDirectly(gotoCommand);
                client.player.sendMessage(Text.literal("§c[魔塔助手] goto 未到达目标，超时重发寻路 (" + bossGotoRetryCount + "/" + BOSS_GOTO_RETRY_LIMIT + ")..."), false);
            } else {
                bossGotoRetryCount = 0;
                client.player.sendMessage(Text.literal("§c[魔塔助手] 多次寻路超时仍未到达，跳过当前路线并推进下一循环！"), false);
                skipCurrentAndAdvanceNext(client);
            }
        }
    }

    private static void handleBossNavigation(MinecraftClient client, Vec3d playerPos, Vec3d targetPos, AutoState nextState) {
        flightTimeoutTicks++;
        if (flightTimeoutTicks > 400 || navigateDirectToTarget(client, playerPos, targetPos)) {
            switchState(client, nextState);
            chatWaitTicks = 0; bossSpawnedByChat = false; bossKilledByChat = false;
        }
    }

    private static void handle12cBossWait15s(MinecraftClient client, AutoState nextState) {
        chatWaitTicks++;
        if (bossKilledByChat || chatWaitTicks >= 400) {
            if (bossKilledByChat) {
                client.player.sendMessage(Text.literal("§a[魔塔助手] 检测到 12c Boss 已击杀，立刻前往下一 Boss 点！"), false);
            } else {
                client.player.sendMessage(Text.literal("§e[魔塔助手] 20 秒等待计时结束（Boss未刷/已漏），立刻前往下一 Boss 点！"), false);
            }
            switchState(client, nextState);
            flightTimeoutTicks = 0; chatWaitTicks = 0; bossSpawnedByChat = false; bossKilledByChat = false;
        }
    }

    private static void skipCurrentAndAdvanceNext(MinecraftClient client) {
        int copyIndex = COPY_LINES_12C.indexOf(selectedCopyLine);

        if (copyIndex != -1 && copyIndex < COPY_LINES_12C.size() - 1) {
            selectedCopyLine = COPY_LINES_12C.get(copyIndex + 1);
            client.player.sendMessage(Text.literal("§e[循环系统] 跳过当前，推进至同镇线下一分线: §b" + selectedCopyLine), false);
        } else {
            selectedCopyLine = "一线";
            int currentTownIndex = getTownIndex(selectedTownLine);

            if (currentTownIndex < 9) {
                selectedTownLine = TOWN_LINES_1_TO_10.get(currentTownIndex + 1);
            } else {
                selectedTownLine = TOWN_LINES_1_TO_10.get(0);
            }
            client.player.sendMessage(Text.literal("§e[循环系统] 推进至下一镇线: §b" + selectedTownLine), false);
        }

        startMenuSequence();
    }

    private static boolean navigateVerticalFirst(MinecraftClient client, Vec3d playerPos, Vec3d target, double tolerance) {
        if (playerPos.distanceTo(target) < tolerance) {
            resetFlightKeys(client);
            return true;
        }

        double dy = target.y - playerPos.y;
        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;

        if (Math.abs(dy) > 1.5) {
            client.player.setPitch(dy > 0 ? -89.0F : 89.0F);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(dy > 0);
            client.options.sneakKey.setPressed(dy <= 0);
            if (client.player.input != null) {
                client.player.input.pressingForward = false;
                client.player.input.jumping = dy > 0;
                client.player.input.sneaking = dy <= 0;
            }
        } else {
            if (Math.sqrt(dx * dx + dz * dz) > 0.5) {
                float targetYaw = (float) (Math.atan2(dz, dx) * (180 / Math.PI)) - 90.0F;
                client.player.setYaw(targetYaw);
            }
            client.player.setPitch(0.0F);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            if (client.player.input != null) {
                client.player.input.pressingForward = true;
                client.player.input.jumping = false;
                client.player.input.sneaking = false;
            }
        }
        return false;
    }

    private static boolean executeThreeStageFlight(MinecraftClient client, Vec3d playerPos, Vec3d targetPos) {
        if (subFlightStage == 1) {
            double horizDx = targetPos.x - playerPos.x;
            double horizDz = targetPos.z - playerPos.z;
            if (Math.sqrt(horizDx * horizDx + horizDz * horizDz) > 0.5) {
                float targetYaw = (float) (Math.atan2(horizDz, horizDx) * (180 / Math.PI)) - 90.0F;
                client.player.setYaw(targetYaw);
            }

            double horizMoved = stage1StartPos != null ? Math.sqrt(Math.pow(playerPos.x - stage1StartPos.x, 2) + Math.pow(playerPos.z - stage1StartPos.z, 2)) : 0.0;

            if (horizMoved < 2.5) {
                client.player.setPitch(0.0F);
                client.options.forwardKey.setPressed(true);
                if (client.player.input != null) client.player.input.pressingForward = true;
            } else {
                client.player.setPitch(-89.0F);
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(true);
                if (client.player.input != null) {
                    client.player.input.pressingForward = false;
                    client.player.input.jumping = true;
                }

                if (playerPos.y >= SAFE_CRUISE_HEIGHT) {
                    subFlightStage = 2;
                }
            }
            return false;
        }

        if (subFlightStage == 2) {
            double horizDx = targetPos.x - playerPos.x;
            double horizDz = targetPos.z - playerPos.z;
            double horizDist = Math.sqrt(horizDx * horizDx + horizDz * horizDz);

            if (horizDist < 3.0) {
                subFlightStage = 3;
                return false;
            }

            if (horizDist > 0.5) {
                float targetYaw = (float) (Math.atan2(horizDz, horizDx) * (180 / Math.PI)) - 90.0F;
                client.player.setYaw(targetYaw);
            }
            client.player.setPitch(0.0F);
            client.options.forwardKey.setPressed(true);
            if (client.player.input != null) client.player.input.pressingForward = true;

            adjustCruiseHeight(client, playerPos);
            return false;
        }

        if (subFlightStage == 3) {
            if (playerPos.distanceTo(targetPos) < 2.0) {
                resetFlightKeys(client);
                subFlightStage = 1;
                return true;
            }

            navigateToTarget(client, playerPos, targetPos);
            return false;
        }

        return false;
    }

    private static boolean navigateDirectToTarget(MinecraftClient client, Vec3d playerPos, Vec3d target) {
        double dist = playerPos.distanceTo(target);
        if (dist < 1.8) {
            resetFlightKeys(client);
            return true;
        }
        navigateToTarget(client, playerPos, target);
        return false;
    }

    private static void adjustCruiseHeight(MinecraftClient client, Vec3d playerPos) {
        boolean needJump = playerPos.y < SAFE_CRUISE_HEIGHT - 2.0;
        boolean needSneak = playerPos.y > SAFE_CRUISE_HEIGHT + 2.0;
        client.options.jumpKey.setPressed(needJump);
        client.options.sneakKey.setPressed(needSneak);
        if (client.player != null && client.player.input != null) {
            client.player.input.jumping = needJump;
            client.player.input.sneaking = needSneak;
        }
    }

    private static void navigateToTarget(MinecraftClient client, Vec3d playerPos, Vec3d target) {
        double dy = target.y - playerPos.y;
        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        if (horizDist > 0.3) {
            float targetYaw = (float) (Math.atan2(dz, dx) * (180 / Math.PI)) - 90.0F;
            client.player.setYaw(targetYaw);
        }

        float targetPitch = (float) (-(Math.atan2(dy, horizDist) * (180 / Math.PI)));
        if (targetPitch > 89.0F) targetPitch = 89.0F;
        if (targetPitch < -89.0F) targetPitch = -89.0F;

        client.player.setPitch(targetPitch);

        boolean fwd = true;
        boolean jmp = dy > 0.5;
        boolean snk = dy < -0.5;

        client.options.forwardKey.setPressed(fwd);
        client.options.jumpKey.setPressed(jmp);
        client.options.sneakKey.setPressed(snk);

        if (client.player != null && client.player.input != null) {
            client.player.input.pressingForward = fwd;
            client.player.input.jumping = jmp;
            client.player.input.sneaking = snk;
        }
    }

    private static void resetFlightKeys(MinecraftClient client) {
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
        }
        if (client.player != null && client.player.input != null) {
            client.player.input.pressingForward = false;
            client.player.input.pressingBack = false;
            client.player.input.pressingLeft = false;
            client.player.input.pressingRight = false;
            client.player.input.jumping = false;
            client.player.input.sneaking = false;
        }
    }

    private static boolean isLineEmpty(ItemStack stack, MinecraftClient client) {
        if (stack == null || stack.isEmpty()) return false;

        List<Text> tooltipComponents = stack.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);

        StringBuilder fullLore = new StringBuilder();
        boolean hasEmptyKeyword = false;
        boolean hasFullKeyword = false;

        for (Text line : tooltipComponents) {
            String tStr = line.getString().trim();
            fullLore.append("[").append(tStr).append("] ");

            if (tStr.contains("0人") || tStr.contains("0 人") || tStr.contains("无人") || tStr.contains("空闲")) {
                hasEmptyKeyword = true;
            }

            if (tStr.contains("爆满") || tStr.contains("已满") || tStr.contains("拥挤")) {
                hasFullKeyword = true;
            }

            if (tStr.matches(".*[00]\\s*/\\s*\\d+.*") ||
                tStr.matches(".*在线\\s*[:：]\\s*0.*") ||
                tStr.matches(".*人数\\s*[:：]\\s*0.*")) {
                hasEmptyKeyword = true;
            }
        }

        String itemName = stack.getName().getString();
        if (itemName.contains("0人") || itemName.contains("无人") || itemName.contains("空闲")) {
            hasEmptyKeyword = true;
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§7[魔塔调试] 检查分线: §f" + itemName + " §7| Lore: §f" + fullLore + " §7-> §e" + (hasEmptyKeyword && !hasFullKeyword ? "无人(0人)" : "有人")), false);
        }

        return hasEmptyKeyword && !hasFullKeyword;
    }

    private static boolean handleCopyLineSelectionWithDelay(ScreenHandler handler, MinecraftClient client) {
        if (client.interactionManager == null || client.player == null) return false;

        if (currentState == AutoState.WAIT_SPECIAL_SUB_MENU && selectedFloor.isSpecialMenu()) {
            int slotIdx = selectedFloor.getMenuSlotIndex();
            if (slotIdx >= 0 && slotIdx < handler.slots.size()) {
                ItemStack stack = handler.getSlot(slotIdx).getStack();
                if (!stack.isEmpty()) {
                    client.player.sendMessage(Text.literal("§a[魔塔助手] 已选中 " + selectedFloor.getFloorNameCN() + "，正在打开分线菜单..."), false);
                    client.interactionManager.clickSlot(handler.syncId, slotIdx, 0, SlotActionType.PICKUP, client.player);

                    switchState(client, AutoState.WAIT_COPY_LINE_MENU);
                    lineCheckRetryCount = 0;
                    isWaitingForServerLore = false;

                    initCheckOrderList();
                    currentLineIndex = 0;

                    delayTicks = 20;
                    return false;
                }
            }
            return false;
        }

        if (currentCheckOrder.isEmpty()) {
            initCheckOrderList();
        }

        if (!autoSwitchLine || selectedCopyLine.equals("一线")) {
            String targetKeyword = getCurrentKeywordString();
            for (int i = 0; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (!stack.isEmpty() && stack.getName().getString().contains(targetKeyword)) {
                    client.player.sendMessage(Text.literal("§a[魔塔助手] 已直接锁定分线 [" + targetKeyword + "]，直接进入！"), false);
                    client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                    currentCheckOrder.clear();
                    return true;
                }
            }

            if (lineCheckRetryCount < MAX_LINE_RETRIES) {
                lineCheckRetryCount++;
                delayTicks = 10;
                return false;
            }

            client.player.sendMessage(Text.literal("§c[魔塔助手] 错误：未找到目标分线格子，自动跳过并切入下一循环！"), false);
            lineCheckRetryCount = 0;
            currentCheckOrder.clear();
            if (client.currentScreen != null) client.setScreen(null);
            skipCurrentAndAdvanceNext(client);
            return false;
        }

        if (isWaitingForServerLore) {
            loreWaitTicks--;
            if (loreWaitTicks > 0) return false;

            if (targetSlotToCheck >= 0 && targetSlotToCheck < handler.slots.size()) {
                ItemStack stack = handler.getSlot(targetSlotToCheck).getStack();
                if (!stack.isEmpty()) {
                    boolean isEmptyLine = isLineEmpty(stack, client);

                    if (isEmptyLine) {
                        String matchedKeyword = currentCheckOrder.get(currentLineIndex);
                        if (matchedKeyword.contains("A1")) selectedCopyLine = "A1";
                        else if (matchedKeyword.contains("A2")) selectedCopyLine = "A2";
                        else if (matchedKeyword.contains("A3")) selectedCopyLine = "A3";
                        else if (matchedKeyword.contains("一线") || matchedKeyword.contains("1线")) selectedCopyLine = "一线";

                        client.player.sendMessage(Text.literal("§a[魔塔助手] 确认 [" + matchedKeyword + "] 当前无人(0人)！正在进入..."), false);
                        client.interactionManager.clickSlot(handler.syncId, targetSlotToCheck, 0, SlotActionType.PICKUP, client.player);
                        isWaitingForServerLore = false;
                        currentCheckOrder.clear();
                        return true;
                    } else {
                        client.player.sendMessage(Text.literal("§7[魔塔助手] 该分线有人，切至下一个优先级分线..."), false);
                        isWaitingForServerLore = false;
                        currentLineIndex++;
                        if (currentLineIndex >= currentCheckOrder.size()) {
                            client.player.sendMessage(Text.literal("§c[魔塔助手] 抢线结束：本轮候选分线均有人，跳过并切入下一循环！"), false);
                            currentCheckOrder.clear();
                            if (client.currentScreen != null) client.setScreen(null);
                            skipCurrentAndAdvanceNext(client);
                            return false;
                        }
                        delayTicks = 2;
                        return false;
                    }
                }
            }
            isWaitingForServerLore = false;
        }

        if (currentLineIndex >= currentCheckOrder.size()) {
            client.player.sendMessage(Text.literal("§c[魔塔助手] 抢线结束：没有符合条件的分线。"), false);
            currentCheckOrder.clear();
            if (client.currentScreen != null) client.setScreen(null);
            skipCurrentAndAdvanceNext(client);
            return false;
        }

        String currentKeyword = currentCheckOrder.get(currentLineIndex);

        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getName().getString().contains(currentKeyword)) {
                targetSlotToCheck = i;
                isWaitingForServerLore = true;
                loreWaitTicks = 6;
                delayTicks = 0;
                return false;
            }
        }

        if (lineCheckRetryCount < MAX_LINE_RETRIES) {
            lineCheckRetryCount++;
            delayTicks = 10;
            return false;
        }

        client.player.sendMessage(Text.literal("§c[魔塔助手] 错误：遍历分线超时！未找到对应的分线格子。自动跳过..."), false);
        lineCheckRetryCount = 0;
        currentCheckOrder.clear();
        if (client.currentScreen != null) client.setScreen(null);
        skipCurrentAndAdvanceNext(client);
        return false;
    }

 private static String getCurrentKeywordString() {
        String floorPrefix = "传送" + selectedFloor.getFloorNameCN() + "一线";
        return selectedCopyLine.equals("一线") ? floorPrefix : floorPrefix + selectedCopyLine;
    }

    private static void initCheckOrderList() {
        currentCheckOrder.clear();
        String floorPrefix = "传送" + selectedFloor.getFloorNameCN() + "一线";

        if (!autoSwitchLine || selectedCopyLine.equals("一线")) return;

        if (selectedCopyLine.equals("A1")) {
            currentCheckOrder.addAll(Arrays.asList(floorPrefix + "A1", floorPrefix + "A2", floorPrefix + "A3"));
        } else if (selectedCopyLine.equals("A2")) {
            currentCheckOrder.addAll(Arrays.asList(floorPrefix + "A2", floorPrefix + "A1", floorPrefix + "A3"));
        } else if (selectedCopyLine.equals("A3")) {
            currentCheckOrder.addAll(Arrays.asList(floorPrefix + "A3", floorPrefix + "A1", floorPrefix + "A2"));
        }
    }

    private static void executeWurstCommandDirectly(String cmd) {
        String cleanCmd = cmd.trim();
        while (cleanCmd.startsWith(".") || cleanCmd.startsWith("/")) {
            cleanCmd = cleanCmd.substring(1).trim();
        }

        try {
            if (wurstCmdProcessorCache == null || wurstProcessMethodCache == null) {
                Class<?> wurstClass = Class.forName("net.wurstclient.WurstClient");
                Object wurstInstance = wurstClass.getField("INSTANCE").get(null);
                Method getCmdProcessor = wurstClass.getMethod("getCmdProcessor");
                wurstCmdProcessorCache = getCmdProcessor.invoke(wurstInstance);
                wurstProcessMethodCache = wurstCmdProcessorCache.getClass().getMethod("process", String.class);
            }
            wurstProcessMethodCache.invoke(wurstCmdProcessorCache, cleanCmd);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void ensureFlightHackResolved() {
        if (flightHackCache != null || flightHackResolutionAttempted) return;
        flightHackResolutionAttempted = true;
        try {
            Class<?> wurstClass = Class.forName("net.wurstclient.WurstClient");
            Object wurstInstance = wurstClass.getField("INSTANCE").get(null);
            Object hax = wurstClass.getMethod("getHax").invoke(wurstInstance);

            Object flightHack;
            try {
                Field field = hax.getClass().getField("flightHack");
                flightHack = field.get(hax);
            } catch (NoSuchFieldException e) {
                flightHack = hax.getClass().getMethod("getHackByName", String.class).invoke(hax, "Flight");
            }

            if (flightHack == null) return;

            Method getSettingByName = flightHack.getClass().getMethod("getSettingByName", String.class);
            Object h = getSettingByName.invoke(flightHack, "Horizontal speed");
            Object v = getSettingByName.invoke(flightHack, "Vertical speed");

            flightHackCache = flightHack;
            horizontalSpeedSettingCache = h;
            verticalSpeedSettingCache = v;
            flightHackIsEnabledMethodCache = flightHack.getClass().getMethod("isEnabled");
            flightHackSetEnabledMethodCache = flightHack.getClass().getMethod("setEnabled", boolean.class);

            if (h != null) {
                sliderSetValueMethodCache = h.getClass().getMethod("setValue", double.class);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static void setFlightSpeeds() {
        ensureFlightHackResolved();
        try {
            if (horizontalSpeedSettingCache != null && verticalSpeedSettingCache != null && sliderSetValueMethodCache != null) {
                sliderSetValueMethodCache.invoke(horizontalSpeedSettingCache, FLIGHT_HORIZONTAL_SPEED);
                sliderSetValueMethodCache.invoke(verticalSpeedSettingCache, FLIGHT_VERTICAL_SPEED);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static boolean clickSlotByKeyword(ScreenHandler handler, MinecraftClient client, String keyword) {
        if (client.interactionManager == null || client.player == null || keyword == null || keyword.isEmpty()) return false;

        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getName().getString().contains(keyword)) {
                client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                return true;
            }
        }
        return false;
    }

    private static String extractLineNumber(String townLineStr) {
        if (townLineStr == null) return "";
        return townLineStr.replaceAll("[魔塔传送服务器线区]", "").trim();
    }
}