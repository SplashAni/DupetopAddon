package dupetop.addon;

import dupetop.addon.modules.PistonAura;
import dupetop.addon.modules.PistonPush;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Main extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Bypass");

    @Override
    public void onInitialize() {
        LOG.info("Loading DupetopAddon");


        Modules.get().add(new PistonPush());
        Modules.get().add(new PistonAura());

    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dupetop.addon";
    }
}
