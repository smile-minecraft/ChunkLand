package com.smile.chunkland.runtime.rule;

import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Objects;

/**
 * UI-facing consequence text for a closed (DENY) rule: the UI
 * must warn the owner what breaks when they close a rule).
 *
 * <p>Data only — no UI code lives here. Each string describes what stops
 * happening while the rule is DENY, so the owner understands that their own
 * machines and actions are affected too.
 */
public final class LandRuleConsequences {

    private LandRuleConsequences() {
    }

    /**
     * Consequence of setting {@code rule} to DENY.
     *
     * @param rule the rule being closed; must not be {@code null}
     * @return non-blank Traditional Chinese description for UI display
     */
    public static String denyConsequence(LandRuleType rule) {
        Objects.requireNonNull(rule, "rule");
        return switch (rule) {
            case PVP -> "關閉後領地內玩家之間無法互相造成傷害，主人也不例外；想開放切磋請改為允許。";
            case EXPLOSION_TERRAIN -> "關閉後爆炸不會破壞領地內的地形；想用 TNT 開礦請改為允許。";
            case EXPLOSION_ENTITY -> "關閉後爆炸不會傷害領地內的實體，包含主人自己與牲口。";
            case FIRE_SPREAD -> "關閉後火焰不會在領地內蔓延；已燃燒的火源仍需手動撲滅。";
            case FIRE_BURN -> "關閉後火焰不會燒毀領地內的方塊。";
            case MOB_GRIEFING -> "關閉後末影人、苦力怕、凋零等無法破壞或搬動領地內的方塊。";
            case FLUID_FLOW -> "關閉後水與岩漿不會在領地內流動，跨界流入或流出同樣被擋下；水流農場與岩漿機關會停止運作。";
            case PISTON -> "關閉後活塞無法推動或拉回領地內的方塊；主人的自動化機器一樣會停止，跨界推拉同樣被擋下。";
            case HOPPER_TRANSFER -> "關閉後漏斗停止傳輸物品；主人的自動分類與收集系統一樣會停止，跨界傳輸同樣被擋下。";
            case HOSTILE_MOB_SPAWN -> "關閉後敵對生物不會在領地內自然生成；刷怪塔將不再運作。";
            case PASSIVE_MOB_SPAWN -> "關閉後被動生物不會在領地內自然生成；動物農場需改用繁殖維持。";
        };
    }
}
