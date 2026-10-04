package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormSpec;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.PermissionExplain;
import com.smile.chunkland.protection.PermissionExplainLayer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Bedrock forms and the roster pages take their visible copy from an
 * injected text seam: the bundled English wording is the same the forms
 * always had, every key exists in both lang files, and a supplied source
 * replaces all of it.
 */
class BedrockFormTextsTest {

    private static YamlConfiguration load(String localeTag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + localeTag + ".yml"));
        return cfg;
    }

    /** Lang keys declared as constants on a text seam; prefixes excluded. */
    private static List<String> keysOf(Class<?> seam) throws Exception {
        List<String> keys = new ArrayList<>();
        for (Field field : seam.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())) {
                String value = (String) field.get(null);
                if (!value.endsWith(".")) {
                    keys.add(value);
                }
            }
        }
        return keys;
    }

    /** Text source that renders a lang file the way a plain serializer would. */
    private static BedrockFormTexts fromLang(YamlConfiguration cfg) {
        return (key, vars) -> {
            String out = cfg.getString(key, key);
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                out = out.replace("<" + entry.getKey() + ">", String.valueOf(entry.getValue()));
            }
            return out.replaceAll("</?(red|gray|white|yellow|green)>", "");
        };
    }

    private static ManagementGuiModel modelWithOneDeny() {
        return ManagementGuiModel.fromExplains(List.of(new PermissionExplain(
                ProtectionActionType.BLOCK_BREAK, PermissionState.DENY,
                ProtectionActionType.BLOCK_BREAK.decisionSource(),
                PermissionExplainLayer.LAND_DEFAULT, "test", null, false, false, false)),
                Map.of());
    }

    @Test
    void everyFormAndRosterKeyExistsInBothLocales() throws Exception {
        List<String> keys = new ArrayList<>(keysOf(BedrockFormTexts.class));
        keys.addAll(keysOf(ManagementRosterTexts.class));
        for (BedrockManageForms.Capability capability : BedrockManageForms.Capability.values()) {
            keys.add(BedrockFormTexts.MENU_PREFIX + capability.name().toLowerCase(Locale.ROOT));
        }
        keys.add("gui.manage.roster.denied");
        for (String localeTag : new String[] {"en_US", "zh_TW"}) {
            YamlConfiguration cfg = load(localeTag);
            for (String key : keys) {
                assertTrue(cfg.isString(key), localeTag + " missing " + key);
            }
        }
    }

    @Test
    void bundledEnglishCoversEveryKeyItDeclares() throws Exception {
        for (String key : keysOf(BedrockFormTexts.class)) {
            assertNotEquals(key, BedrockFormTexts.english().text(key),
                    "bundled wording missing for " + key);
        }
        for (String key : keysOf(ManagementRosterTexts.class)) {
            assertNotEquals(key, ManagementRosterTexts.english().text(key),
                    "bundled wording missing for " + key);
        }
        for (BedrockManageForms.Capability capability : BedrockManageForms.Capability.values()) {
            String key = BedrockFormTexts.MENU_PREFIX
                    + capability.name().toLowerCase(Locale.ROOT);
            assertNotEquals(key, BedrockFormTexts.english().text(key));
        }
    }

    @Test
    void formsWithoutATextSourceKeepTheirBundledWording() {
        FormSpec.Simple root = BedrockManageForms.rootMenu();
        assertEquals("Land Management", root.title());
        assertEquals(BedrockManageForms.routes().size(), root.buttons().size());
        assertEquals("Trust members", root.buttons().get(3));

        FormSpec.Simple detail = BedrockManageForms.permissionDetail(modelWithOneDeny());
        assertEquals("Land Permissions (1 DENY, 0 ALLOW)", detail.title());
        assertEquals(List.of("BLOCK_BREAK", "Back"), detail.buttons());
        assertEquals("BLOCK_BREAK: DENY @ LAND_DEFAULT", detail.content());

        FormSpec.Simple choice = BedrockManageForms.playerChoiceMenu(List.of("Alice"), "Trust");
        assertEquals("Trust - choose player", choice.title());
        assertEquals(List.of("Alice", "Back"), choice.buttons());
    }

    @Test
    void suppliedTextSourceReplacesEveryVisibleString() throws Exception {
        BedrockFormTexts zh = fromLang(load("zh_TW"));

        FormSpec.Simple root = BedrockManageForms.rootMenu(zh);
        assertEquals("領地管理", root.title());
        assertEquals("成員管理", root.buttons().get(3));
        assertEquals(BedrockManageForms.routes().size(), root.buttons().size(),
                "button order and count must stay tied to the route table");
        for (String button : root.buttons()) {
            assertFalse(button.matches(".*[A-Za-z]{3,}.*"), "untranslated button: " + button);
        }

        FormSpec.Simple trust = BedrockManageForms.trustModeMenu(zh);
        assertEquals(List.of("信任玩家", "取消信任", "返回"), trust.buttons());
        FormSpec.Simple ban = BedrockManageForms.banModeMenu(zh);
        assertEquals(List.of("封鎖玩家", "解除封鎖", "返回"), ban.buttons());

        FormSpec.Simple choice = BedrockManageForms.playerChoiceMenu(
                List.of("Alice", "*", " "), "信任玩家", zh);
        assertEquals("信任玩家：選擇玩家", choice.title());
        assertEquals(List.of("Alice", "返回"), choice.buttons(),
                "reserved names stay filtered whatever the language");
        FormSpec.Simple empty = BedrockManageForms.playerChoiceMenu(List.of(), "信任玩家", zh);
        assertEquals(List.of("返回"), empty.buttons());
    }

    @Test
    void detailButtonsStayIndexAlignedWithTheModelRows() throws Exception {
        BedrockFormTexts zh = fromLang(load("zh_TW"));
        ManagementGuiModel model = modelWithOneDeny();

        FormSpec.Simple detail = BedrockManageForms.permissionDetail(model, zh);

        assertEquals(model.rows().size() + 1, detail.buttons().size());
        assertEquals("破壞方塊", detail.buttons().get(0),
                "a row button shows the permission's display name");
        assertEquals(BedrockManageForms.detailBackButton(detail), model.rows().size(),
                "the back button must stay right after the last row");

        FormSpec.Simple unavailable = BedrockManageForms.permissionDetail(null, zh);
        assertEquals(List.of("返回"), unavailable.buttons());
        assertTrue(unavailable.content().contains("領地資料還沒準備好"), unavailable.content());
    }
}
