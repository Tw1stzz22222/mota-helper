package com.example.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class FloorSelectScreen extends Screen {
    private int floorIndex = 0;
    private int townIndex = 0;
    private int copyIndex = 0;
    private boolean autoSwitch = true;

    private static final ExampleModClient.TargetFloor[] FLOORS = ExampleModClient.TargetFloor.values();
    private static final String[] TOWNS = {
            "魔塔一区", "魔塔二线", "魔塔三线", "魔塔四线", "魔塔五线",
            "魔塔六线", "魔塔七线", "魔塔八线", "魔塔九线", "魔塔十线"
    };
    private static final String[] COPY_LINES = {"一线", "A1", "A2", "A3"};

    public FloorSelectScreen() {
        super(Text.literal("魔塔助手 - 选择目标"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 66;

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("楼层: §e" + FLOORS[floorIndex].getFloorNameCN()),
                        b -> { floorIndex = (floorIndex + 1) % FLOORS.length; this.clearAndInit(); })
                .dimensions(cx - 100, y, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("镇线: §b" + TOWNS[townIndex]),
                        b -> { townIndex = (townIndex + 1) % TOWNS.length; this.clearAndInit(); })
                .dimensions(cx - 100, y + 24, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("分线: §a" + COPY_LINES[copyIndex]),
                        b -> { copyIndex = (copyIndex + 1) % COPY_LINES.length; this.clearAndInit(); })
                .dimensions(cx - 100, y + 48, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("抢线: " + (autoSwitch ? "§a开启" : "§c关闭")),
                        b -> { autoSwitch = !autoSwitch; this.clearAndInit(); })
                .dimensions(cx - 100, y + 72, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§a§l开始自动化"),
                        b -> {
                            ExampleModClient.startFullAutomation(
                                    FLOORS[floorIndex], TOWNS[townIndex], COPY_LINES[copyIndex], autoSwitch);
                            this.close();
                        })
                .dimensions(cx - 100, y + 104, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§7取消"),
                        b -> this.close())
                .dimensions(cx - 100, y + 128, 200, 20)
                .build());
    }
}
