package dev.createguide;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Собирает текстовое описание игровой ситуации. Вызывать из потока клиента. */
public final class ContextBuilder {
    private static final String[] INTERESTING_MODS = {"create", "aeronautic", "simulated", "sable", "propulsion"};

    private ContextBuilder() {}

    public static String build(Minecraft mc) {
        Player p = mc.player;
        Level level = mc.level;
        StringBuilder sb = new StringBuilder("[Контекст игры]\n");
        if (p == null || level == null) return sb.toString();

        sb.append("Измерение: ").append(level.dimension().location()).append('\n');
        BlockPos pos = p.blockPosition();
        sb.append("Позиция игрока: ").append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append('\n');
        sb.append("В основной руке: ").append(describe(p.getMainHandItem())).append('\n');
        sb.append("В левой руке: ").append(describe(p.getOffhandItem())).append('\n');

        HitResult hr = mc.hitResult;
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            describeBlock(sb, level, bhr.getBlockPos());
        } else if (hr instanceof EntityHitResult ehr) {
            sb.append("Игрок смотрит на существо: ").append(ehr.getEntity().getName().getString())
                    .append(" (").append(BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType())).append(")\n");
        } else {
            sb.append("Прицел ни на что не наведён.\n");
        }

        List<String> inv = new ArrayList<>();
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty()) inv.add(describe(s));
        sb.append("Инвентарь (первые 9 слотов — хотбар): ").append(inv.isEmpty() ? "пусто" : String.join("; ", inv)).append('\n');

        List<String> armor = new ArrayList<>();
        for (ItemStack s : p.getInventory().armor) if (!s.isEmpty()) armor.add(describe(s));
        if (!armor.isEmpty()) sb.append("Броня: ").append(String.join("; ", armor)).append('\n');

        List<String> mods = new ArrayList<>();
        for (IModInfo m : ModList.get().getMods()) {
            String id = m.getModId();
            for (String key : INTERESTING_MODS) {
                if (id.contains(key)) {
                    mods.add(id + " " + m.getVersion());
                    break;
                }
            }
        }
        Collections.sort(mods);
        if (!mods.isEmpty()) sb.append("Установленные моды по теме: ").append(String.join(", ", mods)).append('\n');
        return sb.toString();
    }

    private static void describeBlock(StringBuilder sb, Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        sb.append("Игрок смотрит на блок: ").append(st.getBlock().getName().getString())
                .append(" (").append(BuiltInRegistries.BLOCK.getKey(st.getBlock())).append(')');
        if (!st.getProperties().isEmpty()) {
            sb.append(", свойства: ");
            boolean first = true;
            for (Property<?> prop : st.getProperties()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(prop.getName()).append('=').append(valueName(st, prop));
            }
        }
        sb.append('\n');
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            try {
                String nbt = be.saveWithoutMetadata(level.registryAccess()).toString();
                if (nbt.length() > 700) nbt = nbt.substring(0, 700) + "…";
                sb.append("Данные блока (NBT, там может быть скорость вращения и нагрузка): ").append(nbt).append('\n');
            } catch (Throwable ignored) {
                // не критично
            }
        }
    }

    private static <T extends Comparable<T>> String valueName(BlockState st, Property<T> prop) {
        return prop.getName(st.getValue(prop));
    }

    static String describe(ItemStack s) {
        if (s.isEmpty()) return "пусто";
        return s.getHoverName().getString() + " (" + BuiltInRegistries.ITEM.getKey(s.getItem()) + ")"
                + (s.getCount() > 1 ? " x" + s.getCount() : "");
    }
}
